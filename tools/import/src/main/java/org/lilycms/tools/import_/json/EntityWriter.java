package org.lilycms.tools.import_.json;

import org.codehaus.jackson.node.ObjectNode;
import org.lilycms.repository.api.Repository;
import org.lilycms.repository.api.RepositoryException;

public interface EntityWriter<T> {
    public ObjectNode toJson(T entity, Repository repository) throws RepositoryException;

    /**
     * Writes the entity to JSON, but does not include a namespace section into it, rather
     * re-uses the given Namespaces object (the namespaces are assumed to be added to a parent
     * object).
     */
    public ObjectNode toJson(T entity, Namespaces namespaces, Repository repository) throws RepositoryException;
}