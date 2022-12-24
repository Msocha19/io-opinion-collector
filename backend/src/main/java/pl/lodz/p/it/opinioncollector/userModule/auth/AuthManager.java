package pl.lodz.p.it.opinioncollector.userModule.auth;


import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import pl.lodz.p.it.opinioncollector.exceptions.user.EmailAlreadyRegisteredException;
import pl.lodz.p.it.opinioncollector.userModule.dto.LoginDTO;
import pl.lodz.p.it.opinioncollector.userModule.dto.RegisterUserDTO;
import pl.lodz.p.it.opinioncollector.userModule.dto.SuccessfulLoginDTO;
import pl.lodz.p.it.opinioncollector.userModule.token.Token;
import pl.lodz.p.it.opinioncollector.userModule.token.TokenRepository;
import pl.lodz.p.it.opinioncollector.userModule.token.TokenType;
import pl.lodz.p.it.opinioncollector.userModule.user.User;
import pl.lodz.p.it.opinioncollector.userModule.user.UserProvider;
import pl.lodz.p.it.opinioncollector.userModule.user.UserRepository;
import pl.lodz.p.it.opinioncollector.userModule.user.UserType;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Transactional
public class AuthManager {

    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder encoder;
    private final JwtProvider jwtProvider;
    private final MailManager mailManager;
    private final HttpTransport transport = new NetHttpTransport();
    private final JsonFactory factory = new GsonFactory();
    private final RestTemplate restTemplate = new RestTemplate();
    @Value("${backend.url}")
    private String apiUrl;
    @Value("${spring.security.oauth2.client.registration.google.clientId}")
    private String googleClientId;
    @Value("${spring.security.oauth2.client.registration.google.clientSecret}")
    private String googleClientSecret;

    public SuccessfulLoginDTO login(LoginDTO dto) {
        Authentication authentication;
        try {
            authentication = authenticationManager.
                    authenticate(new UsernamePasswordAuthenticationToken(dto.getEmail(), dto.getPassword()));
        } catch (LockedException le) {
            throw new ResponseStatusException(HttpStatus.LOCKED);
        } catch (DisabledException de) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE);
        } catch (AuthenticationException ae) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        User user = (User) authentication.getPrincipal();

        if (user.isDeleted()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        String jwt = jwtProvider.generateJWT(user.getEmail(), user.getRole());
        Token refreshToken = generateAndSaveToken(user, TokenType.REFRESH_TOKEN);
        return new SuccessfulLoginDTO(user.getRole(), jwt, refreshToken.getToken(), user.getEmail(), user.getProvider());
    }

    public User register(RegisterUserDTO dto) throws EmailAlreadyRegisteredException {
        String hashedPassword = encoder.encode(dto.getPassword());

        User user = new User(dto.getEmail(), dto.getUsername(), hashedPassword);
        try {
            userRepository.save(user);
            String verificationToken = generateAndSaveToken(user, TokenType.VERIFICATION_TOKEN).getToken();
            String link = apiUrl + "/confirm/register?token=" + verificationToken;
            mailManager.registrationEmail(user.getEmail(), user.getVisibleName(), link);
            return user;
        } catch (Exception e) {
            throw new EmailAlreadyRegisteredException();
        }
    }

    private Token generateAndSaveToken(User user, TokenType tokenType) {
        Token token = new Token(UUID.randomUUID().toString(), tokenType, user);
        return tokenRepository.save(token);
    }

    public void confirmRegistration(String token) {
        Token verificationToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));

        User user = verificationToken.getUser();
        user.setActive(true);

        userRepository.save(user);
        tokenRepository.delete(verificationToken);
    }

    public SuccessfulLoginDTO refresh(String token) {
        Token t = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));

        User user = t.getUser();

        String newJWT = jwtProvider.generateJWT(user.getEmail(), user.getRole());
        Token newRefreshToken = generateAndSaveToken(user, TokenType.REFRESH_TOKEN);
        tokenRepository.deleteByToken(token);
        tokenRepository.save(newRefreshToken);
        return new SuccessfulLoginDTO(user.getRole(), newJWT, newRefreshToken.getToken(), user.getEmail(), user.getProvider());
    }

    public void confirmDeletion(String token) {
        Token deletionToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));

        tokenRepository.deleteAllByUser(deletionToken.getUser());
        User user = userRepository.findByEmail(deletionToken.getUser().getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        user.setDeleted(true);
    }

    public void dropToken(String token) {
        tokenRepository.deleteByToken(token);
    }

    public void dropAllRefreshTokens() {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        tokenRepository.deleteAllByUserAndType(user, TokenType.REFRESH_TOKEN);
    }

    public SuccessfulLoginDTO authenticateWithGoogle(String code) throws GeneralSecurityException, IOException, IllegalAccessException {

        String id_token = exchangeCodeForIdToken(code);
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(transport, factory).setAudience(Collections.singleton(googleClientId)).build();

        GoogleIdToken idToken = verifier.verify(id_token);
        if (idToken == null) {
            throw new IllegalAccessException("Invalid id_token");
        }

        Optional<User> optionalUser = userRepository.findByEmail(idToken.getPayload().getEmail());
        User user;
        if (optionalUser.isEmpty()) {
            user = new User();
            user.setEmail(idToken.getPayload().getEmail());
            user.setVisibleName(idToken.getPayload().getEmail());
            user.setRole(UserType.USER);
            user.setProvider(UserProvider.GOOGLE);
            user.setActive(true);
            userRepository.save(user);
        } else {
            user = optionalUser.get();
        }

        if (user.isDeleted()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        if (user.isLocked()) {
            throw new ResponseStatusException(HttpStatus.LOCKED);
        }

        String jwt = jwtProvider.generateJWT(user.getEmail(), user.getRole());
        Token refreshToken = generateAndSaveToken(user, TokenType.REFRESH_TOKEN);
        return new SuccessfulLoginDTO(user.getRole(), jwt, refreshToken.getToken(), user.getEmail(), user.getProvider());
    }

    private String exchangeCodeForIdToken(String code) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("grant_type", "authorization_code");
        jsonObject.addProperty("code", code);
        jsonObject.addProperty("client_id", googleClientId);
        jsonObject.addProperty("client_secret", googleClientSecret);
        jsonObject.addProperty("redirect_uri", "http://localhost:8080/api/login/oauth2/code/google");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(jsonObject.toString(), headers);

        String result = restTemplate.postForObject("https://accounts.google.com/o/oauth2/token", request, String.class);
        JsonObject resultJson = new Gson().fromJson(result, JsonObject.class);

        return resultJson.get("id_token").getAsString();
    }
}
