package com.project.demo.controller;

import com.project.demo.model.GeminiModel;
import com.project.demo.model.ModelListResponse;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

@RestController
public class GeminiModelController {
    //private static final Logger logger = LoggerFactory.getLogger(GeminiModelController.class);
    @Value("${spring.ai.openai.api-key}")
    private String GEMINI_API_KEY;
    private final RestClient restClient;

    public GeminiModelController(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    @GetMapping("/models")
    public List<GeminiModel> models(){
        ResponseEntity<ModelListResponse> response = restClient.get()
                .uri("/v1beta/openai/models")
                .header("Authorization", "Bearer " + GEMINI_API_KEY)
                .retrieve()
                .toEntity(ModelListResponse.class);
        return Objects.requireNonNull(response.getBody()).data();
    }


}
