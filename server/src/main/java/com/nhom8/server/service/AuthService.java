package com.nhom8.server.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import com.nhom8.server.model.User;
import com.nhom8.server.repository.UserRepository;
import com.nhom8.common.dto.PlayerDTO;
import com.nhom8.common.message.payload.LoginResultPayload;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AuthService {

    /** Biểu thức chính quy kiểm tra username: chỉ chữ cái, số, gạch dưới, 3-30 ký tự */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,30}$");

    /** Biểu thức chính quy kiểm tra display name: 2-30 ký tự, không chứa ký tự đặc biệt/HTML */
    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{2,30}$");

    /** Giới hạn số lần thử đăng nhập sai liên tiếp */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** Thời gian khóa tài khoản sau khi vượt quá số lần thử (5 phút = 300,000 ms) */
    private static final long LOCK_DURATION_MS = 5 * 60 * 1000L;

    /** Lớp theo dõi số lần đăng nhập sai theo username */
    private static class LoginAttempt {
        int failedCount = 0;
        long lockUntil = 0;
    }

    private final ConcurrentHashMap<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();
    
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Autowired
    @Lazy
    private GameService gameService;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * Xác thực đăng nhập với các lớp bảo vệ:
     * 1. Whitelist validation chống SQL injection và payload độc hại
     * 2. Giới hạn độ dài mật khẩu chống tấn công Hash-DoS vào BCrypt
     * 3. Cơ chế Rate-Limiting chống Brute-Force từ điển
     * 4. Thông báo lỗi chuẩn OWASP chống rò rỉ tài khoản (User Enumeration)
     */
    public LoginResultPayload login(String username, String password) {
        // 1. Kiểm tra định dạng đầu vào (Input Validation & Sanitization)
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Tên đăng nhập không hợp lệ. Chỉ chấp nhận chữ cái, số và gạch dưới (3-30 ký tự).");
        }
        username = username.trim();

        if (password == null || password.isEmpty() || password.length() > 64) {
            throw new IllegalArgumentException("Mật khẩu không hợp lệ (độ dài tối đa 64 ký tự).");
        }

        // 2. Kiểm tra Rate Limiting / Chống Brute Force
        String key = username.toLowerCase();
        long now = System.currentTimeMillis();
        LoginAttempt attempt = loginAttempts.get(key);
        if (attempt != null && attempt.lockUntil > now) {
            long remainingSeconds = (attempt.lockUntil - now) / 1000;
            throw new IllegalArgumentException("Tài khoản tạm thời bị khóa do nhập sai mật khẩu quá " 
                    + MAX_FAILED_ATTEMPTS + " lần. Vui lòng thử lại sau " + remainingSeconds + " giây.");
        }

        // 3. Truy vấn tài khoản và kiểm tra mật khẩu bằng BCrypt
        User user = userRepository.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            recordFailedAttempt(key);
            throw new IllegalArgumentException("Tên đăng nhập hoặc mật khẩu không chính xác.");
        }

        // 4. Đăng nhập thành công -> xóa bộ đếm thất bại
        loginAttempts.remove(key);
        
        PlayerDTO playerDTO = new PlayerDTO(user.getUsername(), user.getDisplayName(), user.getAvatar(), user.getRankingScore(), "ONLINE", false);
        return new LoginResultPayload(playerDTO, null);
    }

    public void register(String username, String password, String displayName) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Tên đăng nhập không hợp lệ. Chỉ chấp nhận chữ cái, số và gạch dưới (3-30 ký tự).");
        }
        username = username.trim();

        if (userRepository.findByUsername(username) != null) {
            throw new IllegalArgumentException("Tên đăng nhập đã được sử dụng.");
        }
        if (password == null || password.length() < 4 || password.length() > 64) {
            throw new IllegalArgumentException("Mật khẩu phải từ 4 đến 64 ký tự.");
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = username;
        }
        displayName = displayName.trim();
        if (!DISPLAY_NAME_PATTERN.matcher(displayName).matches()) {
            throw new IllegalArgumentException("Tên hiển thị chỉ gồm chữ cái, chữ số, gạch dưới (2-30 ký tự).");
        }
        User existingDisplayName = userRepository.findByDisplayName(displayName);
        if (existingDisplayName != null) {
            throw new IllegalArgumentException("Tên hiển thị đã được sử dụng.");
        }

        String hashedPassword = passwordEncoder.encode(password);
        User newUser = new User(username, hashedPassword, displayName);
        userRepository.save(newUser);
    }

    private void recordFailedAttempt(String usernameKey) {
        loginAttempts.compute(usernameKey, (k, current) -> {
            if (current == null) {
                current = new LoginAttempt();
                current.failedCount = 1;
            } else {
                current.failedCount++;
                if (current.failedCount >= MAX_FAILED_ATTEMPTS) {
                    current.lockUntil = System.currentTimeMillis() + LOCK_DURATION_MS;
                    current.failedCount = 0;
                }
            }
            return current;
        });
    }
}
