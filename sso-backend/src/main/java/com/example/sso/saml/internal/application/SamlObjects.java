package com.example.sso.saml.internal.application;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.XMLObjectBuilder;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeValue;

import javax.xml.namespace.QName;
import java.util.Objects;

/**
 * Small factory helpers over the OpenSAML builder registry. Requires OpenSAML to be
 * initialized (see {@link OpenSamlConfig}).
 */
public final class SamlObjects {

    private SamlObjects() {
    }

    /** Builds any SAML {@link XMLObject} by its default element {@link QName}. */
    @SuppressWarnings("unchecked")
    public static <T extends XMLObject> T build(QName elementName) {
        XMLObjectBuilderFactory factory = XMLObjectProviderRegistrySupport.getBuilderFactory();
        XMLObjectBuilder<?> builder = Objects.requireNonNull(
                factory.getBuilder(elementName), () -> "No OpenSAML builder for " + elementName);
        return (T) builder.buildObject(elementName);
    }

    /** Builds a single-valued SAML {@link Attribute} carrying a string value. */
    @SuppressWarnings("unchecked")
    public static Attribute stringAttribute(String name, String value) {
        Attribute attribute = build(Attribute.DEFAULT_ELEMENT_NAME);
        attribute.setName(name);
        attribute.setNameFormat(Attribute.URI_REFERENCE);

        XMLObjectBuilder<XSString> stringBuilder = (XMLObjectBuilder<XSString>)
                XMLObjectProviderRegistrySupport.getBuilderFactory().getBuilder(XSString.TYPE_NAME);
        XSString attributeValue = stringBuilder.buildObject(AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME);
        attributeValue.setValue(value);
        attribute.getAttributeValues().add(attributeValue);
        return attribute;
    }
}
