package com.example.Campagin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScimUserResponse {

    private String id;
    private String userName;
    private String displayName; // ده الحقل اللي يهمنا
    private List<Email> emails;
}
