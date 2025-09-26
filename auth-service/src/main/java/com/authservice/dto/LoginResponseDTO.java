package com.authservice.dto;

public class LoginResponseDTO {
  private final String token;
  private final String userId;

  public LoginResponseDTO(String token, String userId) {
    this.token = token;
    this.userId=userId;
  }

  public String getToken() {
    return token;
  }
  public String getUserId() {
    return userId;
  }
}