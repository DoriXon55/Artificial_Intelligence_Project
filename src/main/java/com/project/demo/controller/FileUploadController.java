package com.project.demo.controller;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.demo.exceptions.StorageFileNotFoundException;
import com.project.demo.service.StorageService;
import io.micrometer.core.instrument.Metrics;
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
    
    @GetMapping("/api/preferences")
    @ResponseBody
    public Map<String, String> getUserPreferences(HttpSession session) {
        Map<String, String> preferences = new HashMap<>();
        UserPreference userPref = GeminiModelController.getUserPreference(session.getId());
        
        if (userPref != null) {
            preferences.put("method", userPref.getProcessingMethod());
            preferences.put("modelId", userPref.getModelId());
        } else {
            preferences.put("method", "python");
            preferences.put("modelId", "");
        }
        
        return preferences;
    }
    
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

            storageService.store(file);

            String filePath = storageService.getStorageLocation().resolve(originalFileName).toAbsolutePath().toString();

            UserPreference preference = GeminiModelController.getUserPreference(session.getId());
            
            String transcription;
            String summary;
            Map<String, Double> metrics;
            boolean useGemini = false;
            String modelId = null;
            
            if (preference != null && "gemini".equals(preference.getProcessingMethod())) {
                useGemini = true;
                modelId = preference.getModelId();

                String jsonOutput = processPythonScript(filePath);
                ObjectMapper mapper = new ObjectMapper();
                JsonNode resultJson = mapper.readTree(jsonOutput);
                
                if (resultJson.has("error")) {
                    throw new RuntimeException("Python script error: " + resultJson.get("error").asText());
                }
                
                transcription = resultJson.get("transcription").asText();

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
                
                metrics = calculateMetrics(transcription, geminiSummary);
                
            } else {
                String jsonOutput = processPythonScript(filePath);
                ObjectMapper mapper = new ObjectMapper();
                JsonNode resultJson = mapper.readTree(jsonOutput);
                
                if (resultJson.has("error")) {
                    throw new RuntimeException("Python script error: " + resultJson.get("error").asText());
                }
                
                transcription = resultJson.get("transcription").asText();
                summary = resultJson.get("summary").asText();
                metrics = calculateMetrics(transcription, summary);
            }

            redirectAttributes.addFlashAttribute("transcription", transcription);
            redirectAttributes.addFlashAttribute("summary", summary);
            redirectAttributes.addFlashAttribute("metrics", metrics);
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


    private Map<String, Double> calculateMetrics(String originalText, String summaryText) {
        Map<String, Double> metrics = new HashMap<>();

        String[] originalWords = originalText.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s]", "")
                .split("\\s+");

        String[] summaryWords = summaryText.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s]", "")
                .split("\\s+");

        // Calculate ROUGE-1 (unigrams)
        Set<String> originalUnigrams = new HashSet<>(Arrays.asList(originalWords));
        Set<String> summaryUnigrams = new HashSet<>(Arrays.asList(summaryWords));

        // Calculate intersections
        int matchingUnigrams = 0;
        for (String unigram : summaryUnigrams) {
            if (originalUnigrams.contains(unigram)) {
                matchingUnigrams++;
            }
        }

        double rouge1Precision = summaryUnigrams.isEmpty() ? 0.0 :
                (matchingUnigrams / (double) summaryUnigrams.size()) * 100;
        double rouge1Recall = originalUnigrams.isEmpty() ? 0.0 :
                (matchingUnigrams / (double) originalUnigrams.size()) * 100;
        double rouge1F1 = (rouge1Precision + rouge1Recall > 0) ?
                2 * (rouge1Precision * rouge1Recall) / (rouge1Precision + rouge1Recall) : 0.0;

        // Round to 2 decimal places
        metrics.put("precision", Math.round(rouge1Precision * 100.0) / 100.0);
        metrics.put("recall", Math.round(rouge1Recall * 100.0) / 100.0);
        metrics.put("fScore", Math.round(rouge1F1 * 100.0) / 100.0);

        return metrics;
    }
    private String processPythonScript(String filePath) throws IOException, InterruptedException {
        Process process = getProcess(filePath);

        BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder stdoutOutput = new StringBuilder();
        String line;
        while ((line = stdoutReader.readLine()) != null) {
            stdoutOutput.append(line);
        }


        String jsonOutput = stdoutOutput.toString().trim();
        if (jsonOutput.isEmpty()) {
            throw new RuntimeException("No output received from Python script");
        }
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

        processBuilder.redirectErrorStream(false);

        Process process = processBuilder.start();
        return process;
    }



    @ExceptionHandler(StorageFileNotFoundException.class)
    public ResponseEntity<?> handleStorageFileNotFound(StorageFileNotFoundException exc)
    {
        return ResponseEntity.notFound().build();
    }



}
