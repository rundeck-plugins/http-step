package edu.ohio.ais.rundeck;

import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.plugins.configuration.Property;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class HttpDescriptionTest {

    private Description description;

    @Before
    public void setUp() {
        description = new HttpDescription("test-provider", "Test Title", "Test Description").getDescription();
    }

    private Property property(String name) {
        for (Property property : description.getProperties()) {
            if (name.equals(property.getName())) {
                return property;
            }
        }
        return null;
    }

    @Test
    public void describes_authenticationWithBearerValue() {
        Property property = property("authentication");

        assertNotNull("Expected an authentication property", property);
        assertEquals(Arrays.asList(HttpBuilder.AUTH_NONE, HttpBuilder.AUTH_BASIC, HttpBuilder.AUTH_BEARER,
                        HttpBuilder.AUTH_OAUTH2),
                property.getSelectValues());
    }

    @Test
    public void describes_authenticationDefaultsToNone() {
        Property property = property("authentication");

        assertNotNull("Expected an authentication property", property);
        assertEquals(HttpBuilder.AUTH_NONE, property.getDefaultValue());
    }

    @Test
    public void describes_noPasswordBearerTokenProperty() {
        // Replaced by the "Bearer" authentication value.
        assertNull(property("passwordBearerToken"));
    }
}
