package de.example.vk.service;

import de.example.vk.repository.UserRepository;
import de.example.vk.util.HtmlSanitizer;
import de.example.vk.util.PasswordHasher;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Prüft die Anmeldedaten im aktuellen Mandanten; gibt den Benutzer oder null zurück. */
    public UserRepository.UserRow authenticate(String email, String password) {
        if (email == null || password == null) {
            return null;
        }
        UserRepository.UserRow user = userRepository.findActiveByEmail(email);
        if (user == null || !PasswordHasher.verify(password, user.passwordHash)) {
            return null;
        }
        return user;
    }

    /** Registriert einen neuen Selbsteintrags-Nutzer. Wirft ValidationException bei Problemen. */
    public long register(String email, String displayName, String password) {
        String mail = email == null ? "" : email.trim();
        if (!mail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ValidationException("email", "Bitte eine gültige E-Mail-Adresse angeben.");
        }
        if (password == null || password.length() < 8) {
            throw new ValidationException("password", "Das Passwort muss mindestens 8 Zeichen haben.");
        }
        String name = HtmlSanitizer.stripAll(displayName == null ? "" : displayName.trim());
        if (name.isEmpty()) {
            throw new ValidationException("displayName", "Bitte einen Anzeigenamen angeben.");
        }
        if (userRepository.emailExists(mail)) {
            throw new ValidationException("email", "Diese E-Mail-Adresse ist bereits registriert.");
        }
        return userRepository.createRegisteredUser(mail, name, PasswordHasher.hash(password));
    }
}
