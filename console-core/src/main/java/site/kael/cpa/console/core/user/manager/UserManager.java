package site.kael.cpa.console.core.user.manager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;

import java.util.List;
import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.auth.manager.PersistentLoginTokenManager;
import site.kael.cpa.console.core.crypto.ApiKeyCrypto;
import site.kael.cpa.console.core.user.dao.UserDao;
import site.kael.cpa.console.core.user.model.User;
import site.kael.cpa.console.core.user.model.UserRole;
import site.kael.cpa.console.core.user.exception.UserNotFoundException;

@Component
public class UserManager {
    private final UserDao userDao;
    private final ApiKeyCrypto apiKeyCrypto;
    private final CpaApiKeyManager cpaApiKeyManager;
    private final PersistentLoginTokenManager persistentLoginTokenManager;

    public UserManager(UserDao userDao, ApiKeyCrypto apiKeyCrypto) {
        this(userDao, apiKeyCrypto, null, null);
    }

    public UserManager(UserDao userDao, ApiKeyCrypto apiKeyCrypto, CpaApiKeyManager cpaApiKeyManager) {
        this(userDao, apiKeyCrypto, cpaApiKeyManager, null);
    }

    @Autowired
    public UserManager(
            UserDao userDao,
            ApiKeyCrypto apiKeyCrypto,
            CpaApiKeyManager cpaApiKeyManager,
            PersistentLoginTokenManager persistentLoginTokenManager
    ) {
        this.userDao = userDao;
        this.apiKeyCrypto = apiKeyCrypto;
        this.cpaApiKeyManager = cpaApiKeyManager;
        this.persistentLoginTokenManager = persistentLoginTokenManager;
    }

    public User findOrCreateByApiKey(String apiKey) {
        String hash = apiKeyCrypto.hash(apiKey);
        return userDao.findByApiKeyHash(hash).orElseGet(() -> createUser(apiKey, hash));
    }

    public User findById(long id) {
        return userDao.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    public List<User> findAll() {
        return userDao.findAll();
    }

    public User create(String nickname, UserRole role) {
        String normalizedNickname = normalizeNickname(nickname);
        UserRole normalizedRole = requireRole(role);
        if (cpaApiKeyManager == null) {
            throw new IllegalStateException("CPA API key manager is not configured");
        }
        // Deliberately do not wrap this in a database transaction: if the local insert
        // fails after CPA succeeds, the CPA key must remain as required by the API contract.
        String apiKey = cpaApiKeyManager.create();
        return userDao.insert(normalizedNickname, normalizedRole, apiKeyCrypto.hash(apiKey), apiKeyCrypto.encrypt(apiKey));
    }

    public User update(long id, String nickname, UserRole role) {
        return userDao.update(id, normalizeNickname(nickname), requireRole(role));
    }

    public void delete(long id) {
        userDao.delete(id);
        if (persistentLoginTokenManager != null) persistentLoginTokenManager.revokeAllForUser(id);
    }

    public String apiKey(User user) {
        return apiKeyCrypto.decrypt(user.apiKeyCiphertext());
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("Nickname is required");
        }
        return nickname.trim();
    }

    private UserRole requireRole(UserRole role) {
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }
        return role;
    }

    private synchronized User createUser(String apiKey, String hash) {
        return userDao.findByApiKeyHash(hash).orElseGet(() -> {
            UserRole role = userDao.count() == 0 ? UserRole.ADMIN : UserRole.USER;
            String nickname = role == UserRole.ADMIN ? "管理员" : "用户";
            try {
                return userDao.insert(nickname, role, hash, apiKeyCrypto.encrypt(apiKey));
            } catch (DuplicateKeyException exception) {
                return userDao.findByApiKeyHash(hash).orElseThrow(() -> exception);
            }
        });
    }
}
