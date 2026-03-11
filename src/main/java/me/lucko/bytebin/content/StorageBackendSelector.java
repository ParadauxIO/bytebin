package me.lucko.bytebin.content;

import me.lucko.bytebin.content.storage.StorageBackend;

/**
 * Selects the backend to store content in.
 */
public interface StorageBackendSelector {

    /**
     * Select which backend to store {@code content} in.
     *
     * @param content the content
     * @return the selected backend
     */
    StorageBackend select(Content content);

    final class Static implements StorageBackendSelector {
        private final StorageBackend backend;

        public Static(StorageBackend backend) {
            this.backend = backend;
        }

        @Override
        public StorageBackend select(Content content) {
            return this.backend;
        }
    }

}
