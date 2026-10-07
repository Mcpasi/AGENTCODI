package de.agentcodi.tests;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class ChineseLanguageResourcesTest {
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$([ds])|%)");

    private ChineseLanguageResourcesTest() {
    }

    public static int run() throws Exception {
        Map<String, Element> defaults = resources("values");
        Map<String, Element> chinese = resources("values-b+zh+Hans");
        coversEveryInterfaceResource(defaults, chinese);
        preservesFormatArguments(defaults, chinese);
        return 2;
    }

    private static void coversEveryInterfaceResource(
        Map<String, Element> defaults,
        Map<String, Element> chinese
    ) {
        TestSupport.assertEquals(defaults.keySet(), chinese.keySet(), "complete Chinese UI");
        for (String name : defaults.keySet()) {
            Element source = defaults.get(name);
            Element translation = chinese.get(name);
            TestSupport.assertEquals(
                source.getTagName(), translation.getTagName(), "resource kind: " + name
            );
            if ("false".equals(source.getAttribute("translatable"))) {
                TestSupport.assertEquals(
                    source.getTextContent(), translation.getTextContent(),
                    "unchanged technical identifier: " + name
                );
            }
            TestSupport.assertFalse(
                text(translation).trim().isEmpty(), "Chinese text exists: " + name
            );
        }
        TestSupport.assertEquals(
            "简体中文", text(chinese.get("language_simplified_chinese")), "native language label"
        );
    }

    private static void preservesFormatArguments(
        Map<String, Element> defaults,
        Map<String, Element> chinese
    ) {
        for (String name : defaults.keySet()) {
            String source = text(defaults.get(name));
            String translation = text(chinese.get(name));
            TestSupport.assertEquals(
                formatTokens(source), formatTokens(translation),
                "format argument indices/types and escaped percent signs: " + name
            );
            Object[] arguments = new Object[5];
            Matcher matcher = FORMAT.matcher(source);
            while (matcher.find()) {
                if (matcher.group(1) != null) {
                    int index = Integer.parseInt(matcher.group(1)) - 1;
                    arguments[index] = "d".equals(matcher.group(2))
                        ? Integer.valueOf(17) : "示例";
                }
            }
            String.format(Locale.SIMPLIFIED_CHINESE, translation, arguments);
        }
    }

    private static List<String> formatTokens(String value) {
        List<String> tokens = new ArrayList<String>();
        Matcher matcher = FORMAT.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        Collections.sort(tokens);
        return tokens;
    }

    private static String text(Element resource) {
        if (!"plurals".equals(resource.getTagName())) {
            return resource.getTextContent();
        }
        NodeList items = resource.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if ("other".equals(item.getAttribute("quantity"))) {
                // Chinese uses "other" for every quantity, including zero and one.
                return item.getTextContent();
            }
        }
        throw new AssertionError("Missing other plural: " + resource.getAttribute("name"));
    }

    private static Map<String, Element> resources(String qualifier) throws Exception {
        Path root = Paths.get(System.getProperty("agentcodi.projectRoot", "."));
        Path path = root.resolve("app/src/main/res/" + qualifier + "/strings.xml");
        Element resources = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(path.toFile()).getDocumentElement();
        Map<String, Element> result = new LinkedHashMap<String, Element>();
        NodeList children = resources.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element resource = (Element) child;
                String name = resource.getAttribute("name");
                TestSupport.assertEquals(
                    null, result.put(name, resource), "unique resource name: " + name
                );
            }
        }
        return result;
    }
}
