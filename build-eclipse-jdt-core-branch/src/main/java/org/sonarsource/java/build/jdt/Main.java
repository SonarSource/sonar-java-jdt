package org.sonarsource.java.build.jdt;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static org.sonarsource.java.build.jdt.SubProcess.exec;

public class Main {

  public static final String JDT_CORE_REPOSITORY = "https://github.com/eclipse-jdt/eclipse.jdt.core.git";
  public static final String PROJECT_DIR_NAME = "build-eclipse-jdt-core-branch";

  public static void main(String[] args) throws IOException, InterruptedException, ParserConfigurationException, TransformerException, SAXException, XPathExpressionException {
    if (args.length != 1) {
      System.err.println("Error, JDT git branch or commit reference");
      System.exit(1);
    }
    String jdtCheckoutReference = args[0];
    checkJavaVersion("21");
    Path projectDir = findProjectDir();
    Path eclipseSettings = projectDir.resolve(Path.of("src", "main", "resources", "settings.xml"));
    Path workDir = prepareWorkDir(projectDir);
    Path parentSrcDir = projectDir.getParent().resolve("src");
    if (Files.exists(parentSrcDir) && parentSrcDir.getParent().getFileName().toString().equals("sonar-java-jdt")) {
      deleteDirectory(parentSrcDir);
    }
    Path jdtDir = cloneOrCheckoutJdtCoreDir(workDir, jdtCheckoutReference);
    buildJdtCore(jdtDir);
    var jdtCoreArtifact = new Artifact("org.eclipse.jdt", "org.eclipse.jdt.core", "3.41.50-SNAPSHOT");
    String excludes = "" +
      "org.eclipse.platform:org.eclipse.core.filesystem," +
      "org.eclipse.platform:org.eclipse.core.expressions," +
      "org.eclipse.platform:org.eclipse.equinox.registry," +
      "org.eclipse.platform:org.eclipse.equinox.app," +
      "net.java.dev.jna:jna," +
      "org.eclipse.platform:org.eclipse.core.commands";
    JsonObject dependencyTree = dependencyTree(jdtCoreArtifact, excludes, eclipseSettings);
    Set<Artifact> dependencies = dependencyList(dependencyTree);
    replaceSonarJavaJdtDependencies(projectDir.getParent(), dependencies, eclipseSettings);
    rebuildSonarJavaJdt(projectDir.getParent());
  }

  private static void rebuildSonarJavaJdt(Path sonarJavaJdtPath) throws IOException, InterruptedException {
    System.out.println("Rebuild sonar-java-jdt");
    exec(sonarJavaJdtPath, "mvn",
      // we want a fresh build
      "clean",
      // we want to install the artifacts in the maven local repository
      "install",
      // we don't want to see the build download progress
      "--batch-mode",
      // we don't want to use repox
      "-Dskip-sonarsource-repo=true")
      .throwIfFailed("Failed to build SonarJava JDT");
  }

  private static void deleteDirectory(Path dir) throws IOException {
    try (var paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder())
        .map(Path::toFile)
        .forEach(File::delete);
    }
  }

  private static void replaceSonarJavaJdtDependencies(Path sonarJavaJdtDir, Set<Artifact> dependencies, Path eclipseSettings)
    throws IOException, SAXException, ParserConfigurationException, TransformerException, XPathExpressionException {
    Path pomPath = sonarJavaJdtDir.resolve("pom.xml");
    Document doc = loadXml(pomPath);
    Element projectElement = doc.getDocumentElement();
    projectElement.normalize();
    removeEmptyTextNodes(projectElement);
    Element oldDependencies = findChild(projectElement, "dependencies");
    if (oldDependencies == null) {
      throw new IllegalStateException("No 'dependencies' element found in " + pomPath);
    }

    removeNodeWithXPath(doc, "/project/dependencyManagement");
    removeNodeWithXPath(doc, "/project/repositories");
    removeNodeWithXPath(doc, "/project/build/plugins/" +
      "plugin[artifactId/text()='maven-shade-plugin']/executions/execution/configuration/filters/" +
      "filter[artifact/text()='org.eclipse.jdt:*']");

    Document settingsDocument = loadXml(eclipseSettings);
    Element repositories = findElementWithXPath(settingsDocument, "/settings/profiles/profile/repositories");
    projectElement.insertBefore(removeEmptyTextNodes(doc.importNode(repositories, true)), oldDependencies);

    Element newDependencies = replaceElement(doc, projectElement, oldDependencies, "dependencies");
    for (Artifact dependency : dependencies) {
      createElement(doc, newDependencies, "dependency",
        dependencyElement -> {
          createElement(doc, dependencyElement, "groupId", dependency.groupId());
          createElement(doc, dependencyElement, "artifactId", dependency.artifactId());
          createElement(doc, dependencyElement, "version", dependency.version());
          createElement(doc, dependencyElement, "exclusions",
            exclusions -> createElement(doc, exclusions, "exclusion",
              exclusion -> {
                createElement(doc, exclusion, "groupId", "*");
                createElement(doc, exclusion, "artifactId", "*");
              }));
        });
    }

    saveXml(pomPath, doc);
  }

  private static Document loadXml(Path xmlPath) throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    return builder.parse(xmlPath.toFile());
  }

  private static void saveXml(Path path, Document doc) throws IOException, TransformerException {
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    try (var pomOutput = Files.newOutputStream(path)) {
      StreamResult result = new StreamResult(pomOutput);
      DOMSource source = new DOMSource(doc);
      transformer.transform(source, result);
    }
  }

  private static <T extends Node> T removeEmptyTextNodes(T node) {
    NodeList childNodes = node.getChildNodes();
    for (int i = 0; i < childNodes.getLength(); i++) {
      Node child = childNodes.item(i);
      if (child.getNodeType() == Node.TEXT_NODE && child.getNodeValue().trim().isEmpty()) {
        node.removeChild(child);
        i--;
      } else if (child.getNodeType() == Node.ELEMENT_NODE) {
        removeEmptyTextNodes(child);
      }
    }
    return node;
  }

  private static Element findChild(Element parent, String name) {
    NodeList childNodes = parent.getChildNodes();
    for (int i = 0; i < childNodes.getLength(); i++) {
      Node child = childNodes.item(i);
      if (child instanceof Element element && name.equals(child.getNodeName())) {
        return element;
      }
    }
    return null;
  }

  private static Element findElementWithXPath(Node parent, String xpath) throws XPathExpressionException {
    XPathExpression expr = XPathFactory.newInstance().newXPath().compile(xpath);
    return (Element) expr.evaluate(parent, XPathConstants.NODE);
  }

  private static int removeNodeWithXPath(Node parent, String xpath) throws XPathExpressionException {
    XPathExpression expr = XPathFactory.newInstance().newXPath().compile(xpath);
    NodeList nodes = (NodeList) expr.evaluate(parent, XPathConstants.NODESET);
    for (int i = 0; i < nodes.getLength(); i++) {
      Node filter = nodes.item(i);
      Node filters = filter.getParentNode();
      filters.removeChild(filter);
    }
    return nodes.getLength();
  }

  private static Element createElement(Document doc, Element parent, String name) {
    Element element = doc.createElement(name);
    parent.appendChild(element);
    return element;
  }

  private static Element createElement(Document doc, Element parent, String name, Consumer<Element> consumer) {
    Element element = doc.createElement(name);
    parent.appendChild(element);
    consumer.accept(element);
    return element;
  }

  private static Element createElement(Document doc, Element parent, String name, String textContent) {
    Element element = createElement(doc, parent, name);
    element.setTextContent(textContent);
    return element;
  }

  private static Element replaceElement(Document doc, Element parent, Element oldElement, String name) {
    Element element = doc.createElement(name);
    parent.replaceChild(element, oldElement);
    return element;
  }

  private static String asString(JsonObject json, String name) {
    JsonElement element = json.get(name);
    return element != null ? element.getAsString() : null;
  }

  private static Set<Artifact> dependencyList(JsonObject json) {
    Set<Artifact> dependencies = new LinkedHashSet<>();
    String groupId = asString(json, "groupId");
    String artifactId = asString(json, "artifactId");
    String version = asString(json, "version");
    if (groupId != null &&
      artifactId != null &&
      version != null &&
      !"true".equals(asString(json, "optional")) &&
      !"test".equals(asString(json, "scope")) &&
      !"system".equals(asString(json, "scope")) &&
      "jar".equals(asString(json, "type"))) {
      dependencies.add(new Artifact(groupId, artifactId, version));
    }
    JsonArray children = json.getAsJsonArray("children");
    if (children != null) {
      for (JsonElement child : children) {
        dependencies.addAll(dependencyList(child.getAsJsonObject()));
      }
    }
    return dependencies;
  }

  private static JsonObject dependencyTree(Artifact artifact, String excludes, Path eclipseSettings) throws IOException, InterruptedException {
    Path outputFile = artifact.directory().resolve("dependency-tree.json");
    exec(artifact.directory(), "mvn",
      "--batch-mode",
      "--settings", eclipseSettings.toString(),
      "--file", artifact.pom().toString(),
      "-Dskip-sonarsource-repo=true",
      "dependency:tree",
      "-Dexcludes=" + excludes,
      "-DoutputEncoding=UTF-8",
      "-DoutputFile=" + outputFile,
      "-DoutputType=json",
      "-Dscope=compile")
      .throwIfFailed("Failed to execute dependency:tree on " + artifact);
    String json = Files.readString(outputFile);
    return new Gson()
      .fromJson(json, JsonObject.class);
  }

  private static void checkJavaVersion(String expectedVersion) throws IOException, InterruptedException {
    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome == null) {
      throw new IllegalStateException("JAVA_HOME is not set");
    }
    Path java = Path.of(javaHome, "bin", "java");
    if (!Files.exists(java)) {
      java = Path.of(javaHome, "bin", "java.exe");
      if (!Files.exists(java)) {
        throw new IllegalStateException("JAVA_HOME does not point to a valid JDK");
      }
    }
    var out = exec(java.toString(),
      // we want a fresh build
      "--version")
      .throwIfFailed("Failed to execute java --version");

    Matcher majorVersionMatcher = Pattern.compile("([0-9]+)\\.[0-9]+").matcher(out.stdout());
    if (!majorVersionMatcher.find()) {
      throw new IllegalStateException("Failed to extract major version from " + out.stdout());
    }
    String majorVersion = majorVersionMatcher.group(1);
    if (!expectedVersion.equals(majorVersion)) {
      throw new IllegalStateException("Running bin/java defined by JAVA_HOME=" + javaHome +
        " has major version " + majorVersion + " but we expected " + expectedVersion);
    }
  }

  private static void buildJdtCore(Path jdtDir) throws IOException, InterruptedException {
    System.out.println("Build " + jdtDir);
    exec(jdtDir, "mvn",
      // we want a fresh build
      "clean",
      // we want to install the artifacts in the maven local repository
      "install",
      // we don't want to see the build download progress
      "--batch-mode",
      // we don't want to use repox
      "-Dskip-sonarsource-repo=true",
      // we don't want to run the tests
      "-DskipTests",
      // do not depend on "org.eclipse.jdt:ecj" from maven repository that does not yet exists
      "-DlocalEcjVersion=99.99",
      // we want to publish in maven repository some pom files with the dependencies
      // extracted from the tycho-p2 configuration and converted into regular maven dependency definitions
      "-Dtycho.mapP2Dependencies=true", "-Dtycho.addMavenDescriptor=true",
      // we only need to build up to "org.eclipse.jdt.core" module, and "--also-make" will build its dependencies first
      // but "org.eclipse.jdt.core.compiler.batch" is deployed as "ecj" in maven central, this confuses the maven dependency build logic
      "--projects", "org.eclipse.jdt:org.eclipse.jdt.core.compiler.batch,org.eclipse.jdt:org.eclipse.jdt.core", "--also-make")
      .throwIfFailed("Failed to build " + jdtDir + " repository");
  }

  private static Path cloneOrCheckoutJdtCoreDir(Path workDir, String jdtCheckoutReference) throws IOException, InterruptedException {
    Path jdtDir = workDir.resolve("eclipse.jdt.core");
    if (Files.exists(jdtDir)) {
      System.out.println("Fetching " + jdtDir);
      exec(jdtDir, "git", "fetch")
        .throwIfFailed("Failed to fetch " + jdtDir + " repository");

      System.out.println("Checkout " + jdtCheckoutReference);
      exec(jdtDir, "git", "checkout", jdtCheckoutReference)
        .throwIfFailed("Failed to checkout " + jdtCheckoutReference);

      System.out.println("Rebase " + jdtCheckoutReference);
      exec(jdtDir, "git", "rebase")
        .throwIfFailed("Failed to rebase" + jdtDir);
    } else {
      System.out.println("Cloning " + JDT_CORE_REPOSITORY + " into " + jdtDir);
      exec("git",
        "clone",
        "--branch", jdtCheckoutReference,
        JDT_CORE_REPOSITORY,
        jdtDir.toString())
        .throwIfFailed("Failed to clone " + JDT_CORE_REPOSITORY + " repository");
    }
    return jdtDir;
  }

  private static Path prepareWorkDir(Path projectDir) throws IOException {
    Path workDir = projectDir.resolve("workdir");
    if (!Files.exists(workDir)) {
      Files.createDirectory(workDir);
    }
    return workDir;
  }

  private static Path findProjectDir() throws IOException {
    var dir = Path.of(".").toRealPath();
    if (!dir.getFileName().toString().equals(PROJECT_DIR_NAME)) {
      dir = dir.resolve(PROJECT_DIR_NAME);
      if (!Files.exists(dir)) {
        throw new IllegalArgumentException("Should be run in the " + PROJECT_DIR_NAME + " directory");
      }
    }
    return dir;
  }

  public record Artifact(String groupId, String artifactId, String version) {
    public Path directory() {
      return Path.of(
        System.getProperty("user.home"),
        ".m2",
        "repository",
        groupId.replace('.', '/'),
        artifactId,
        version);
    }

    public Path pom() {
      return directory().resolve(artifactId + "-" + version + ".pom");
    }
  }
}
