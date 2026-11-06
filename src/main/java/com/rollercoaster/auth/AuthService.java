package com.rollercoaster.auth;

public interface AuthService {

    AuthDtos.AuthResponse signup(AuthDtos.SignupRequest request);

    AuthDtos.AuthResponse login(AuthDtos.LoginRequest request);

    AuthDtos.UserDto getCurrentUser();

    void logout();

    void logout(String token);
}
