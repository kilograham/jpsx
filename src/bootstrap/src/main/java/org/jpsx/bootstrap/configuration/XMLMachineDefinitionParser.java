/*
 * Copyright (C) 2003, 2014 Graham Sanderson
 *
 * This file is part of JPSX.
 * 
 * JPSX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPSX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JPSX.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpsx.bootstrap.configuration;

import org.apache.log4j.Logger;
import org.jpsx.api.InvalidConfigurationException;
import org.jpsx.bootstrap.util.CollectionsFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.Map;
import java.util.Properties;

public class XMLMachineDefinitionParser {
    public static final String CATEGORY = "XML-Config";
    private static Logger log = Logger.getLogger(CATEGORY);

    public static final String ROOT_ELEMENT = "jpsx";
    public static final String MACHINE_ELEMENT = "machine";
    public static final String MACHINE_ID_ATTRIBUTE = "id";
    public static final String COMPONENT_ELEMENT = "component";
    public static final String COMPONENT_ID_ATTRIBUTE = "id";
    public static final String COMPONENT_CLASS_NAME_ATTRIBUTE = "classname";
    public static final String COMPONENT_SET_ELEMENT = "components";
    public static final String COMPONENT_SET_ID_ATTRIBUTE = "id";
    public static final String INCLUDE_ELEMENT = "include";
    public static final String INCLUDE_REF_ID_ATTRIBUTE = "refid";
    public static final String VAR_ELEMENT = "var";
    public static final String VAR_NAME_ATTRIBUTE = "name";
    public static final String VAR_VALUE_ATTRIBUTE = "value";
    public static final String PROPERTY_ELEMENT = "property";
    public static final String PROPERTY_NAME_ATTRIBUTE = "name";
    public static final String PROPERTY_VALUE_ATTRIBUTE = "value";

    private Properties vars;
    /**
     * Map from id to component for those components which have ids.
     */
    private Map<String, ComponentDefinition> componentsById;
    private XPath xPath = XPathFactory.newInstance().newXPath();
    private Element rootElement;

    public MachineDefinition parse(Element element, String machineId, Properties vars) throws InvalidConfigurationException {
        MachineDefinition rc = new MachineDefinition();

        componentsById = CollectionsFactory.newHashMap();
        rootElement = getUniqueElement(element, "/" + ROOT_ELEMENT, "root <jpsx> element");
        this.vars = vars;

        Element machineElement = getUniqueElement(rootElement, MACHINE_ELEMENT + "[@" + MACHINE_ID_ATTRIBUTE + "='" + machineId + "']", "machine with id " + machineId);
        parseComponents(rc, machineElement);
        return rc;
    }

    private void parseComponents(MachineDefinition machine, Element containerElement) {
        NodeList nodes = containerElement.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                Element element = (Element) nodes.item(i);
                if (element.getTagName().equals(COMPONENT_ELEMENT)) {
                    parseComponentElement(machine, element);
                } else if (element.getTagName().equals(INCLUDE_ELEMENT)) {
                    String id = element.getAttribute(INCLUDE_REF_ID_ATTRIBUTE);
                    id = resolveVariables(id);
                    Element refElement = getUniqueElement(
                            rootElement,
                            COMPONENT_ELEMENT + "[@" + COMPONENT_ID_ATTRIBUTE + "='" + id + "']|" + COMPONENT_SET_ELEMENT + "[@" + COMPONENT_SET_ID_ATTRIBUTE + "='" + id + "']",
                            "referenced component/nodes with id " + id);
                    if (refElement.getTagName().equals(COMPONENT_ELEMENT)) {
                        parseComponentElement(machine, refElement);
                    } else {
                        parseComponents(machine, refElement);
                    }
                } else if (element.getTagName().equals(VAR_ELEMENT)) {
                    String name = element.getAttribute(VAR_NAME_ATTRIBUTE);
                    String value = element.getAttribute(VAR_VALUE_ATTRIBUTE);
                    if (!name.equals("")) {
                        value = resolveVariables(value);
                        if (value.equals("")) vars.remove(name);
                        else vars.setProperty(name, value);
                    }
                } else {
                    throw new InvalidConfigurationException("Unexpected element " + element.getTagName());
                }
            }
        }
    }

    private void parseComponentElement(MachineDefinition machine, Element componentElement) {
        String id = componentElement.hasAttribute(COMPONENT_ID_ATTRIBUTE) ? componentElement.getAttribute(COMPONENT_ID_ATTRIBUTE) : null;
        String className = componentElement.getAttribute(COMPONENT_CLASS_NAME_ATTRIBUTE);

        if (className.equals("")) {
            throw new InvalidConfigurationException("Machine has component (id=" + id + ") with no className");
        }

        ComponentDefinition component = addComponent(machine, id, componentElement.getAttribute(COMPONENT_CLASS_NAME_ATTRIBUTE));
        parseConfiguration(component, componentElement);
    }

    /**
     * Handle xml configuration for this component; note this should not have actual
     * side effects other than recording the information. This method is called before init
     * <p/>
     * This implementation looks for &lt;property name="" value=""/&gt; elements and adds
     * them to the properties collection.
     * <p/>
     * Subclasses may override this method to parse other configuration data
     *
     * @param element
     */
    private void parseConfiguration(ComponentDefinition component, Element element) {
        NodeList nodes = element.getElementsByTagName(PROPERTY_ELEMENT);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                Element prop = (Element) node;
                if (prop.hasAttribute(PROPERTY_NAME_ATTRIBUTE) && prop.hasAttribute(PROPERTY_VALUE_ATTRIBUTE)) {
                    String value = prop.getAttribute(PROPERTY_VALUE_ATTRIBUTE);
                    value = resolveVariables(value);
                    if (!value.equals("")) {
                        component.setProperty(prop.getAttribute(PROPERTY_NAME_ATTRIBUTE), value);
                    }
                }
            }
        }
    }

    private Element getUniqueElement(Element context, String path, String description) {
        NodeList nodes;
        try {
            nodes = (NodeList) xPath.evaluate(path, context, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new InvalidConfigurationException("XPath Error", e);
        }
        if (nodes == null || nodes.getLength() == 0) {
            throw new InvalidConfigurationException("Can't find: " + description);
        }
        if (nodes.getLength() > 1) {
            log.warn("More than one node found for search: " + description);
        }
        Node node = nodes.item(0);
        if (node instanceof Element) {
            return (Element) node;
        }
        throw new InvalidConfigurationException("Match for '" + description + "' was not an element");
    }

    /**
     * Substitutes any variables for ${varname}
     *
     * @param value
     * @return the value with substitutions
     */
    public String resolveVariables(String value) {
        StringBuffer rc = new StringBuffer();
        int index = 0;
        while (true) {
            int start = value.indexOf("${", index);
            if (start == -1) break;
            int end = value.indexOf("}");
            if (end == -1) break;
            rc.append(value.substring(index, start));
            String varName = value.substring(start + 2, end);
            String varValue = getVariable(varName);
            if (varValue != null) {
                rc.append(varValue);
            }
            index = end + 1;
        }
        rc.append(value.substring(index));
        return rc.toString();
    }

    public String getVariable(String name) {
        return vars.getProperty(name);
    }

    public ComponentDefinition addComponent(MachineDefinition machine, String id, String className) {
        ComponentDefinition component = new ComponentDefinition(className);
        if (id != null) {
            ComponentDefinition oldComponent = componentsById.get(id);
            if (oldComponent != null) {
                machine.removeComponent(oldComponent);
            }
            componentsById.put(id, component);
        }
        machine.addComponent(component);
        return component;
    }
}
