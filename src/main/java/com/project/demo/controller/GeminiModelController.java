package com.project.demo.controller;

import com.project.demo.model.GeminiModel;
import com.project.demo.model.ModelListResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;

@Controller // Allow usage of html templates
public class GeminiModelController {
    @Value("${spring.ai.openai.api-key}")
    private String GEMINI_API_KEY;
    
    @Value("${spring.ai.openai.chat.options.model}")
    private String DEFAULT_MODEL;
    
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    
    // Keep track of user preferences (in a real app, this would be in a database)
    private static final Map<String, UserPreference> userPreferences = new HashMap<>();
    
    @Autowired
    public GeminiModelController(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
        this.objectMapper = objectMapper;
    }

    // Keep original JSON endpoint but at a different URL
    @GetMapping("/api/models")
    @ResponseBody
    public List<GeminiModel> apiModels() {
        ResponseEntity<ModelListResponse> response = restClient.get()
                .uri("/v1beta/openai/models")
                .header("Authorization", "Bearer " + GEMINI_API_KEY)  // Zachowanie poprzedniego formatu
                .retrieve()
                .toEntity(ModelListResponse.class);
        return Objects.requireNonNull(response.getBody()).data();
    }
    
    @GetMapping("/models")
    public String modelsPage(Model model) {
        ResponseEntity<ModelListResponse> response = restClient.get()
                .uri("/v1beta/openai/models")
                .header("Authorization", "Bearer " + GEMINI_API_KEY)  // Zachowanie poprzedniego formatu
                .retrieve()
                .toEntity(ModelListResponse.class);
        
        model.addAttribute("models", Objects.requireNonNull(response.getBody()).data());
        return "models";
    }
    
    @PostMapping("/api/process")
    @ResponseBody
    public Map<String, Object> processText(@RequestBody Map<String, Object> request, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String sessionId = session.getId();
        
        try {
            String prompt = (String) request.get("prompt");
            String method = (String) request.get("method");
            String modelId = (String) request.get("modelId");
            Boolean savePreference = (Boolean) request.getOrDefault("savePreference", false);
            
            // Save user preference if requested
            if (savePreference) {
                userPreferences.put(sessionId, new UserPreference(method, modelId));
                result.put("preferenceSaved", true);
            }
            
            if ("python".equals(method)) {
                // Process with Python script
                result.putAll(processPythonText(prompt));
            } else {
                // Process with Gemini
                result.put("response", processWithGemini(prompt, modelId));
            }
            
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    private Map<String, Object> processPythonText(String text) throws IOException {
        Map<String, Object> result = new HashMap<>();
        
        // Create a temporary file to hold the text
        Path tempFile = Files.createTempFile("text_to_process", ".txt");
        Files.writeString(tempFile, text);
        
        try {
            // Call Python script with the temp file path
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "python",
                    "main.py",
                    tempFile.toString()
            );
            
            processBuilder.redirectErrorStream(false);
            Process process = processBuilder.start();
            
            // Read output
            BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = stdoutReader.readLine()) != null) {
                output.append(line);
            }
            
            // Parse JSON output
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> pythonResult = mapper.readValue(output.toString(), Map.class);
            
            result.put("transcription", pythonResult.getOrDefault("transcription", "No transcription available"));
            result.put("summary", pythonResult.getOrDefault("summary", "No summary available"));
            
        } catch (Exception e) {
            result.put("error", "Error processing text with Python script: " + e.getMessage());
        } finally {
            // Delete temporary file
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                // Just log, don't fail the whole operation
                System.err.println("Failed to delete temporary file: " + e.getMessage());
            }
        }
        
        return result;
    }
    
    public String processWithGemini(String text, String modelId) {
        try {
            // Logowanie do debugowania
            System.out.println("Calling Gemini API with text: " + text.substring(0, Math.min(50, text.length())) + "...");
            System.out.println("Using model: " + (modelId != null ? modelId : DEFAULT_MODEL));
            
            // Poprawny format dla Gemini API
            ObjectNode requestBody = objectMapper.createObjectNode();
            
            // Tworzenie tablicy contents zawierającej jeden element z polami role i parts
            ObjectNode contentNode = objectMapper.createObjectNode();  // Zmieniono nazwę z content na contentNode
            
            // Tworzenie tablicy parts zawierającej element text
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("text", text);
            
            // Dodanie części do tablicy parts
            contentNode.set("parts", objectMapper.createArrayNode().add(textPart));
            
            // Dodanie role (opcjonalne, ale może być wymagane)
            contentNode.put("role", "user");
            
            // Dodanie content do tablicy contents
            requestBody.set("contents", objectMapper.createArrayNode().add(contentNode));
            
            // Konfiguracja generacji
            ObjectNode generationConfig = requestBody.putObject("generationConfig");
            generationConfig.put("temperature", 0.7);
            generationConfig.put("maxOutputTokens", 800);
            
            // Use the model ID provided or fall back to default
            String model = modelId != null && !modelId.isEmpty() ? modelId : DEFAULT_MODEL;
            
            // Print request for debugging
            System.out.println("Request body: " + requestBody.toPrettyString());
            
            // Make API request with x-goog-api-key header
            ResponseEntity<Map> response = restClient.post()
                    .uri("/v1/models/" + model + ":generateContent")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("x-goog-api-key", GEMINI_API_KEY)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(Map.class);
            
            // Log response for debugging
            System.out.println("API response status: " + response.getStatusCode());
            if (response.getBody() != null) {
                System.out.println("Response contains keys: " + response.getBody().keySet());
            }
            
            // Extract the response text from Gemini's response format
            if (response.getBody() != null && response.getBody().containsKey("candidates")) {
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
                if (!candidates.isEmpty()) {
                    Map<String, Object> firstCandidate = candidates.get(0);
                    if (firstCandidate.containsKey("content")) {
                        Map<String, Object> responseContent = (Map<String, Object>) firstCandidate.get("content");  // Zmieniono nazwę z content na responseContent
                        if (responseContent.containsKey("parts")) {
                            List<Map<String, Object>> parts = (List<Map<String, Object>>) responseContent.get("parts");
                            if (!parts.isEmpty() && parts.get(0).containsKey("text")) {
                                return (String) parts.get(0).get("text");
                            }
                        }
                    }
                }
            }
            
            return "No valid response from Gemini API. Response: " + response.getBody();
            
        } catch (Exception e) {
            e.printStackTrace();  // Dodaj pełny stack trace, aby zobaczyć dokładnie, gdzie występuje błąd
            return "Error calling Gemini API: " + e.getMessage();
        }
    }
    
    // Method to get the user's processing preference
    public static UserPreference getUserPreference(String sessionId) {
        return userPreferences.get(sessionId);
    }
    
    // Class to store user preferences
    public static class UserPreference {
        private final String processingMethod;
        private final String modelId;
        
        public UserPreference(String processingMethod, String modelId) {
            this.processingMethod = processingMethod;
            this.modelId = modelId;
        }
        
        public String getProcessingMethod() {
            return processingMethod;
        }
        
        public String getModelId() {
            return modelId;
        }
    }

    // Dodaj getter dla mapy preferencji
    public static Map<String, UserPreference> getUserPreferences() {
        return userPreferences;
    }
}
