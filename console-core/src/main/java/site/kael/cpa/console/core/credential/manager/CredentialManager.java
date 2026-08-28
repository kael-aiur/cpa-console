package site.kael.cpa.console.core.credential.manager;

import org.springframework.stereotype.Component;
import site.kael.cpa.console.core.cpa.manager.CpaApiKeyManager;
import site.kael.cpa.console.core.cpa.exception.CpaManagementException;
import site.kael.cpa.console.core.cpa.exception.CpaUnavailableException;
import site.kael.cpa.console.core.credential.dao.CredentialDao;
import site.kael.cpa.console.core.credential.model.Credential;

import java.util.List;
import java.util.Optional;

@Component
public class CredentialManager {
    private final CredentialDao credentialDao;
    private final CpaApiKeyManager cpaApiKeyManager;

    public CredentialManager(CredentialDao credentialDao, CpaApiKeyManager cpaApiKeyManager) {
        this.credentialDao = credentialDao;
        this.cpaApiKeyManager = cpaApiKeyManager;
    }

    public synchronized List<Credential> synchronizeAndFindAll() {
        try {
            return credentialDao.synchronize(cpaApiKeyManager.listCredentials());
        } catch (CpaManagementException | CpaUnavailableException exception) {
            // A CPA outage must not make the admin console lose its last known snapshot.
            return credentialDao.findAll();
        }
    }

    public Optional<Credential> findByReferenceId(String referenceId) {
        return credentialDao.findByReferenceId(referenceId);
    }

    public List<Credential> findAllLocal() {
        return credentialDao.findAll();
    }

    public Credential updateTags(long id, List<String> tags) {
        return credentialDao.updateTags(id, tags);
    }
}
