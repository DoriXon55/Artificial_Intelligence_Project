package com.project.demo.controller;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


@Controller
public class FileUploadController {
    private final StorageService storageService;

    @Autowired
    public FileUploadController(StorageService storageService) {
        this.storageService = storageService;
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


    @PostMapping("/")
    public String handleFileUpload(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {
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

            // Wywołaj skrypt Python
            String jsonOutput = processPythonScript(filePath);

            // Przetwórz wynik JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode resultJson = mapper.readTree(jsonOutput);

            if (resultJson.has("error")) {
                throw new RuntimeException("Python script error: " + resultJson.get("error").asText());
            }

            // Zapisz transkrypcję i podsumowanie jako oddzielne atrybuty
            String transcription = resultJson.get("transcription").asText();
            String summary = resultJson.get("summary").asText();

            redirectAttributes.addFlashAttribute("transcription", transcription);
            redirectAttributes.addFlashAttribute("summary", summary);
            redirectAttributes.addFlashAttribute("processResult", true);
            redirectAttributes.addFlashAttribute("message", "You successfully uploaded " + originalFileName + " and processed it!");

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

        // Odczyt błędów (logi)
        BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        StringBuilder stderrOutput = new StringBuilder();
        while ((line = stderrReader.readLine()) != null) {
            stderrOutput.append(line).append("\n");
            System.err.println("Python stderr: " + line);  // Logowanie błędów
        }

        // Czekanie na zakończenie procesu
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.err.println("Python failed with exit code: " + exitCode);
            System.err.println("Error output: " + stderrOutput.toString());
            throw new RuntimeException("Python script failed with exit code " + exitCode + "\nError details: " + stderrOutput.toString());
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
                "C:\\Users\\doria\\IdeaProjects\\AIProject\\main.py",
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
