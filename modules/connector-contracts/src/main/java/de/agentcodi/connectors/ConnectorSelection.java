package de.agentcodi.connectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, bounded and credential-free connector selection for one chat input. */
public final class ConnectorSelection {
    public static final int MAXIMUM_SELECTED = 2;
    public static final int MAXIMUM_ID_CHARACTERS = 256;
    public static final int MAXIMUM_NAME_CHARACTERS = 160;

    private final ConnectorProvider provider;
    private final String id;
    private final String name;

    public ConnectorSelection(ConnectorProvider provider, String id, String name) {
        if (provider == null || !isSafeId(id) || !isSafeName(name)) {
            throw new IllegalArgumentException("Connector selection is invalid");
        }
        this.provider = provider;
        this.id = id;
        this.name = name;
    }

    public ConnectorProvider getProvider() {
        return provider;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public static List<ConnectorSelection> copyOf(List<ConnectorSelection> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        if (values.size() > MAXIMUM_SELECTED) {
            throw new IllegalArgumentException("Too many connectors selected");
        }
        List<ConnectorSelection> copy = new ArrayList<ConnectorSelection>(values.size());
        Set<ConnectorProvider> providers = new HashSet<ConnectorProvider>();
        Set<String> ids = new HashSet<String>();
        for (ConnectorSelection value : values) {
            if (value == null || !providers.add(value.provider) || !ids.add(value.id)) {
                throw new IllegalArgumentException("Connector selections must be unique");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    public static boolean isSafeId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAXIMUM_ID_CHARACTERS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '-' && character != '_' && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeName(String value) {
        if (value == null || value.trim().isEmpty()
            || value.length() > MAXIMUM_NAME_CHARACTERS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof ConnectorSelection)) {
            return false;
        }
        ConnectorSelection other = (ConnectorSelection) value;
        return provider == other.provider && id.equals(other.id) && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        int result = provider.hashCode();
        result = 31 * result + id.hashCode();
        result = 31 * result + name.hashCode();
        return result;
    }
}
