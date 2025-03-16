package com.project.demo.controller;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.demo.exceptions.StorageFileNotFoundException;
import com.project.demo.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.project.demo.controller.GeminiModelController.UserPreference;
import jakarta.servlet.http.HttpSession;


@Controller
public class FileUploadController {
    private final StorageService storageService;
    private final GeminiModelController geminiModelController;

    @Autowired
    public FileUploadController(StorageService storageService, GeminiModelController geminiModelController) {
        this.storageService = storageService;
        this.geminiModelController = geminiModelController;
    }

    @GetMapping("/")
    public String listUploadedFiles(Model model) throws IOException {

        model.addAttribute("files", storageService.loadAll().map(
                        path -> MvcUriComponentsBuilder.fromMethodName(FileUploadController.class,
                                "serveFile", path.getFileName().toString()).build().toUri().toString())
                .collect(Collectors.toList()));

        return "uploadForm";
    }

    @GetMapping("/files/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) {

        Resource file = storageService.loadAsResource(filename);

        if (file == null)
            return ResponseEntity.notFound().build();

        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + file.getFilename() + "\"").body(file);
    }
    
    // New endpoint to get user preferences
    @GetMapping("/api/preferences")
    @ResponseBody
    public Map<String, String> getUserPreferences(HttpSession session) {
        Map<String, String> preferences = new HashMap<>();
        UserPreference userPref = GeminiModelController.getUserPreference(session.getId());
        
        if (userPref != null) {
            preferences.put("method", userPref.getProcessingMethod());
            preferences.put("modelId", userPref.getModelId());
        } else {
            // Default preferences
            preferences.put("method", "python");
            preferences.put("modelId", "");
        }
        
        return preferences;
    }
    
    // New endpoint to save user preferences
    @PostMapping("/api/preferences")
    @ResponseBody
    public Map<String, Boolean> saveUserPreferences(@RequestBody Map<String, String> request, HttpSession session) {
        String method = request.get("method");
        String modelId = request.get("modelId");
        
        // Save preference
        GeminiModelController.getUserPreferences().put(
            session.getId(), 
            new UserPreference(method, modelId)
        );
        
        Map<String, Boolean> response = new HashMap<>();
        response.put("success", true);
        return response;
    }

    @PostMapping("/")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, 
                                   RedirectAttributes redirectAttributes,
                                   HttpSession session) {
        try {
            if(file.isEmpty()) {
                redirectAttributes.addFlashAttribute("message", "Please select a file");
                return "redirect:/";
            }

            String originalFileName = file.getOriginalFilename();
            if (originalFileName == null || !originalFileName.toLowerCase().endsWith(".mp3")) {
                redirectAttributes.addFlashAttribute("message", "Please select a .mp3 file");
                return "redirect:/";
            }

            // Zapisz plik
            storageService.store(file);

            // Pobierz ścieżkę do zapisanego pliku
            String filePath = storageService.getStorageLocation().resolve(originalFileName).toAbsolutePath().toString();

            // Check user preference for processing method
            UserPreference preference = GeminiModelController.getUserPreference(session.getId());
            
            String transcription;
            String summary;
            boolean useGemini = false;
            String modelId = null;
            
            if (preference != null && "gemini".equals(preference.getProcessingMethod())) {
                useGemini = true;
                modelId = preference.getModelId();
                
                // Process with Gemini
                // This would require extracting audio to text first, then sending to Gemini
                // This is a simplified version
                
                // First get transcription with Python script
                String jsonOutput = processPythonScript(filePath);
                ObjectMapper mapper = new ObjectMapper();
                JsonNode resultJson = mapper.readTree(jsonOutput);
                
                if (resultJson.has("error")) {
                    throw new RuntimeException("Python script error: " + resultJson.get("error").asText());
                }
                
                transcription = resultJson.get("transcription").asText();
                
                // Then summarize with Gemini
                // Call GeminiModelController to process the transcription
                String geminiSummary = "";
                try {
                    geminiSummary = this.geminiModelController.processWithGemini(
                        "Summarize the following transcription into a concise paragraph: " + transcription, 
                        modelId
                    );
                } catch (Exception e) {
                    geminiSummary = "Error using Gemini API. Falling back to Python summary: " + e.getMessage();
                    geminiSummary += "\n\n" + resultJson.get("summary").asText();
                }
                
                summary = geminiSummary;
                
            } else {
                // Default to Python script processing
                String jsonOutput = processPythonScript(filePath);
                ObjectMapper mapper = new ObjectMapper();
                JsonNode resultJson = mapper.readTree(jsonOutput);
                
                if (resultJson.has("error")) {
                    throw new RuntimeException("Python script error: " + resultJson.get("error").asText());
                }
                
                transcription = resultJson.get("transcription").asText();
                summary = resultJson.get("summary").asText();
            }

            redirectAttributes.addFlashAttribute("transcription", transcription);
            redirectAttributes.addFlashAttribute("summary", summary);
            redirectAttributes.addFlashAttribute("processResult", true);
            redirectAttributes.addFlashAttribute("useGemini", useGemini);
            redirectAttributes.addFlashAttribute("modelId", modelId);
            redirectAttributes.addFlashAttribute("message", 
                "You successfully uploaded " + originalFileName + " and processed it!");

            return "redirect:/";
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("message", "Error occurred: " + e.getMessage());
            return "redirect:/";
        }
    }
    private String processPythonScript(String filePath) throws IOException, InterruptedException {
        Process process = getProcess(filePath);

        // Odczyt standardowego wyjścia (JSON)
        BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder stdoutOutput = new StringBuilder();
        String line;
        while ((line = stdoutReader.readLine()) != null) {
            stdoutOutput.append(line);
        }


        // Sprawdź czy mamy jakieś wyjście
        String jsonOutput = stdoutOutput.toString().trim();
        if (jsonOutput.isEmpty()) {
            throw new RuntimeException("No output received from Python script");
        }

        // Jeśli wszystko się powiodło, zwróć JSON
        return jsonOutput;
    }

    private static Process getProcess(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File does not exist: " + filePath);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                "python",  // lub pełna ścieżka do Python
                "main.py",
                filePath
        );

        // NIE przekierowuj stderr do stdout - chcemy je rozdzielić
        processBuilder.redirectErrorStream(false);

        // Uruchomienie procesu
        Process process = processBuilder.start();
        return process;
    }

    private String processResult(String jsonOutput) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode resultJson = mapper.readTree(jsonOutput);

            if (resultJson.has("error")) {
                throw new RuntimeException("Python script error: " + resultJson.get("error").asText());
            }

            String transcription = resultJson.get("transcription").asText();
            String summary = resultJson.get("summary").asText();

            return "Transcription:\n" + transcription + "\n\nSummary:\n" + summary;
        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + jsonOutput);
            throw new RuntimeException("Error processing Python script output: " + e.getMessage(), e);
        }
    }


    @ExceptionHandler(StorageFileNotFoundException.class)
    public ResponseEntity<?> handleStorageFileNotFound(StorageFileNotFoundException exc)
    {
        return ResponseEntity.notFound().build();
    }



}
