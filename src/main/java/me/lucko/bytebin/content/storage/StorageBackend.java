package me.lucko.bytebin.content.storage;

import me.lucko.bytebin.content.Content;

import java.util.stream.Stream;

/**
 * The storage backend interface.
 */
public interface StorageBackend {

    /**
     * Get the id of the backend.
     *
     * @return the id
     */
    String getBackendId();

    /**
     * Loads content from the backend.
     *
     * @param key the key to identify the content
     * @return the content, or null
     * @throws Exception catch all
     */
    Content load(String key) throws Exception;

    /**
     * Saves content to the backend.
     *
     * @param content the content
     * @throws Exception catch all
     */
    void save(Content content) throws Exception;

    /**
     * Deletes content from the backend.
     *
     * @param key the key to identify the content
     * @throws Exception catch all
     */
    void delete(String key) throws Exception;

    /**
     * Lists the keys for all content stored in the backend.
     *
     * @return a list of keys
     * @throws Exception catch all
     */
    Stream<String> listKeys() throws Exception;

    /**
     * Lists metadata about all the content stored in the backend. (doesn't load the actual data).
     * Used primarily if the index needs to be rebuilt.
     *
     * @return a list of metadata
     * @throws Exception catch all
     */
    Stream<Content> list() throws Exception;

}
