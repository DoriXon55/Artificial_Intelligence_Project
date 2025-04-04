package com.project.demo.controller;

import com.project.demo.model.GeminiModel;
import com.project.demo.model.ModelListResponse;
import org.hibernate.validator.internal.constraintvalidators.bv.notempty.NotEmptyValidatorForArraysOfBoolean;
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
import java.util.*;

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
    
    private static final Map<String, UserPreference> userPreferences = new HashMap<>();
    
    @Autowired
    public GeminiModelController(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
        this.objectMapper = objectMapper;
    }

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
            
            if (savePreference) {
                userPreferences.put(sessionId, new UserPreference(method, modelId));
                result.put("preferenceSaved", true);
            }
            
            if ("python".equals(method)) {
                result.putAll(processPythonText(prompt));
            } else {
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
            
            BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = stdoutReader.readLine()) != null) {
                output.append(line);
            }
            
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> pythonResult = mapper.readValue(output.toString(), Map.class);
            
            result.put("transcription", pythonResult.getOrDefault("transcription", "No transcription available"));
            result.put("summary", pythonResult.getOrDefault("summary", "No summary available"));
            
        } catch (Exception e) {
            result.put("error", "Error processing text with Python script: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                System.err.println("Failed to delete temporary file: " + e.getMessage());
            }
        }
        
        return result;
    }
    
    public String processWithGemini(String text, String modelId) {
        try {
            System.out.println("Calling Gemini API with text: " + text.substring(0, Math.min(50, text.length())) + "...");
            System.out.println("Using model: " + (modelId != null ? modelId : DEFAULT_MODEL));
            
            ObjectNode requestBody = objectMapper.createObjectNode();
            
            ObjectNode contentNode = objectMapper.createObjectNode();
            
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("text", text);
            
            contentNode.set("parts", objectMapper.createArrayNode().add(textPart));
            
            contentNode.put("role", "user");
            
            requestBody.set("contents", objectMapper.createArrayNode().add(contentNode));
            
            ObjectNode generationConfig = requestBody.putObject("generationConfig");
            generationConfig.put("temperature", 0.7);
            generationConfig.put("maxOutputTokens", 800);
            
            String model = modelId != null && !modelId.isEmpty() ? modelId : DEFAULT_MODEL;
            
            System.out.println("Request body: " + requestBody.toPrettyString());
            
            ResponseEntity<Map> response = restClient.post()
                    .uri("/v1/models/" + model + ":generateContent")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("x-goog-api-key", GEMINI_API_KEY)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(Map.class);
            
            System.out.println("API response status: " + response.getStatusCode());
            if (response.getBody() != null) {
                System.out.println("Response contains keys: " + response.getBody().keySet());
            }
            
            if (response.getBody() != null && response.getBody().containsKey("candidates")) {
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
                if (!candidates.isEmpty()) {
                    Map<String, Object> firstCandidate = candidates.get(0);
                    if (firstCandidate.containsKey("content")) {
                        Map<String, Object> responseContent = (Map<String, Object>) firstCandidate.get("content");
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
            e.printStackTrace();
            return "Error calling Gemini API: " + e.getMessage();
        }
    }
    
    public static UserPreference getUserPreference(String sessionId) {
        return userPreferences.get(sessionId);
    }
    
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

    public static Map<String, UserPreference> getUserPreferences() {
        return userPreferences;
    }
}
