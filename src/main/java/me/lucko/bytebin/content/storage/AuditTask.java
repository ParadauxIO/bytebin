package me.lucko.bytebin.content.storage;

import com.google.gson.Gson;
import me.lucko.bytebin.content.ContentIndexDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class AuditTask implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(AuditTask.class);

    private final ContentIndexDatabase index;
    private final List<StorageBackend> backends;

    public AuditTask(ContentIndexDatabase index, List<StorageBackend> backends) {
        this.index = index;
        this.backends = backends;
    }

    @Override
    public void run() {
        try {
            run0();
        } catch (Exception e) {
            LOGGER.error("Error occurred while auditing", e);
        }
    }

    private void run0() throws Exception {
        LOGGER.info("[AUDIT] Starting audit...");

        for (StorageBackend backend : this.backends) {
            String backendId = backend.getBackendId();

            LOGGER.info("[AUDIT] Listing content for backend {}", backendId);
            List<String> keys = backend.listKeys().toList();
            LOGGER.info("[AUDIT] Found {} entries for backend {}", keys.size(), backendId);

            List<String> keysToDelete = keys.stream()
                    .filter(key -> this.index.get(key) == null)
                    .toList();

            LOGGER.info("[AUDIT] Found {} records that exist in the {} backend but not the index!", keysToDelete.size(), backendId);
            LOGGER.info("[AUDIT] " + new Gson().toJson(keysToDelete));
        }

        LOGGER.info("[AUDIT] Finished audit");
    }
}
