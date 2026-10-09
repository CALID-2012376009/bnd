package aQute.p2.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.Processor;
import aQute.bnd.osgi.Resource;
import aQute.bnd.service.Strategy;
import aQute.bnd.test.jupiter.InjectTemporaryDirectory;
import aQute.lib.io.IO;


class P2PublisherTest {

	@InjectTemporaryDirectory
	File tmp;

	@ParameterizedTest
	@ValueSource(strings = {
		"latest", "[1.0.0,2.0.0)"
	})
	void incrementalExportUsesTheIncludedJarVersionInsteadOfTheCurrentProjectVersion(String requestedVersion)
		throws Exception {
		IO.mkdirs(new File(tmp, Workspace.CNFDIR));
		IO.store("", new File(tmp, "cnf/build.bnd"));
		File bundleDir = IO.mkdirs(new File(tmp, "bundle"));
		IO.store("Bundle-SymbolicName: bundle\nBundle-Version: 1.0.0.old\n-resourceonly: true\n"
			+ "-includeresource: marker;literal=content\n", new File(bundleDir, Project.BNDFILE));
		File repositoryDir = IO.mkdirs(new File(tmp, "repository"));
		IO.store("Bundle-Version: 1.0.0\n-resourceonly: true\nfeatures: feature.bndrun\n",
			new File(repositoryDir, Project.BNDFILE));
		IO.store("Bundle-SymbolicName: feature\n-runbundles: bundle;version='" + requestedVersion + "'\n",
			new File(repositoryDir, "feature.bndrun"));

		try (Workspace workspace = new Workspace(tmp)) {
			Project bundle = workspace.getProject("bundle");
			assertThat(bundle.build()).hasSize(1);
			assertThat(bundle.check()).isTrue();
			bundle.setProperty(Constants.BUNDLE_VERSION, "1.0.0.new");
			bundle.propertiesChanged();
			Project repository = workspace.getProject("repository");
			Container resolved = repository.getBundles(Strategy.HIGHEST,
				"bundle;version='" + requestedVersion + "'", null).get(0);
			assertThat(resolved.getVersion()).isEqualTo("1.0.0.new");
			assertThat(resolved.getManifest().getMainAttributes().getValue(Constants.BUNDLE_VERSION))
				.isEqualTo("1.0.0.old");

			Map.Entry<String, Resource> exported = new P2Export(repository, Map.of()).generate();
			assertThat(repository.check()).isTrue();
			assertThat(exported).isNotNull();
			try (Jar jar = new Jar("p2", exported.getValue().openInputStream());
				Jar content = new Jar("content", jar.getResource("content.jar").openInputStream());
				Jar artifacts = new Jar("artifacts", jar.getResource("artifacts.jar").openInputStream());
				Jar feature = new Jar("feature", jar.getResource("features/feature_1.0.0.jar").openInputStream());
				Jar included = new Jar("bundle", jar.getResource("plugins/bundle_1.0.0.old.jar").openInputStream())) {
				String requirementRange = requestedVersion.equals("latest") ? "[1.0.0.old,1.0.0.old]" : requestedVersion;
				assertThat(jar.getResources()).containsKey("plugins/bundle_1.0.0.old.jar")
					.doesNotContainKey("plugins/bundle_1.0.0.new.jar");
				assertThat(included.getManifest().getMainAttributes().getValue(Constants.BUNDLE_VERSION))
					.isEqualTo("1.0.0.old");
				assertThat(IO.collect(content.getResource("content.xml").openInputStream()))
					.contains("<unit id=\"bundle\" version=\"1.0.0.old\"",
						"namespace=\"osgi.bundle\" name=\"bundle\" version=\"1.0.0.old\"",
						"name=\"bundle\" range=\"" + requirementRange + "\"",
						"Bundle-Version: 1.0.0.old")
					.doesNotContain("1.0.0.new");
				assertThat(IO.collect(artifacts.getResource("artifacts.xml").openInputStream()))
					.contains("id=\"bundle\" version=\"1.0.0.old\"")
					.doesNotContain("1.0.0.new");
				assertThat(IO.collect(feature.getResource("feature.xml").openInputStream()))
					.contains("id=\"bundle\" version=\"" + requirementRange + "\"")
					.doesNotContain("1.0.0.new");
			}
		}
	}

	@Test
	void testBasic() throws Exception {
		try (Workspace ws = new Workspace(IO.getFile("testdata/p2-publish/ws-1"))) {
			ws.addBasicPlugin( new P2Exporter());

			Project p = ws.getProject("p1");
			File[] build = p.build();
			assertTrue(p.check());

			File file = p.getFile("generated/bndtools.jar");
			try (Jar jar = new Jar(file);

				Jar artifacts = new Jar("artifacts.jar", jar.getResource("artifacts.jar")
					.openInputStream());
				Jar content = new Jar("content.jar", jar.getResource("content.jar")
					.openInputStream());
				Jar featureMain = new Jar("feature.jar",
					jar.getResource("features/bndtools.main.feature_7.0.0.0000-RC1.jar")
					.openInputStream());
				Jar featurePde = new Jar("feature.jar",
					jar.getResource("features/bndtools.pde.feature_7.0.0.0000-RC1.jar")
					.openInputStream())) {

				Resource metainf = jar.getResource("META-INF/MANIFEST.MF");
				assertNotNull(metainf);
				// notice the timestamp portion 0000 which should be in
				// MANIFEST.MF
				assertEquals("7.0.0.0000-RC1", jar.getManifest()
					.getMainAttributes()
					.getValue(Constants.BUNDLE_VERSION));

				// but in pom.properties we want just
				// 7.0.0-RC1(without the timestamp 0000 portion)
				Resource pomproperties = jar.getResource("META-INF/maven/org.bndtools/org.bndtools.p2/pom.properties");
				assertNotNull(pomproperties);
				try (InputStream is = pomproperties.openInputStream()) {
					String pomprops = new String(is.readAllBytes());
					assertThat(pomprops).contains("groupId=org.bndtools");
					assertThat(pomprops).contains("artifactId=org.bndtools.p2");
					assertThat(pomprops).contains("version=7.0.0-RC1");
				}

				InputStream xmlArtifactsIs = artifacts.getResource("artifacts.xml")
					.openInputStream();
				String xmlArtifacts = new String(xmlArtifactsIs.readAllBytes(), StandardCharsets.UTF_8);
				Assertions.assertThat(xmlArtifacts)
					.contains("<property name=\"pgp.signatures\" value=\"FAKE SIGNATURE\"/>")
					.contains("<property name=\"pgp.publicKeys\" value=\"FAKE PUBLIC KEY\"/>");

				byte[] xmlContent;
				try (InputStream xmlContentIs = content.getResource("content.xml")
					.openInputStream()) {
					xmlContent = xmlContentIs.readAllBytes();
				}

				boolean validateXMLContent = validateXML(new ByteArrayInputStream(xmlContent),
					P2PublisherTest.class.getResourceAsStream("content-schema.xsd"));
				assertTrue("P2 content.xml is invalid", validateXMLContent);
				assertThat(new String(xmlContent, StandardCharsets.UTF_8))
					.contains("<unit id=\"org.slf4j.api\" version=\"1.7.30.v20200204-2150\"")
					.contains("name=\"org.slf4j.api\" range=\"[1.7.30,2.0.0)\"")
					.contains("name=\"org.osgi.service.coordinator\" range=\"[1.0.2,1.1.0)\"")
					.contains("name=\"org.osgi.service.repository\" range=\"[1.1.0,2.0.0)\"");

				InputStream xmlFeatureIs = featureMain.getResource("feature.xml")
					.openInputStream();

				String doctype = "<!DOCTYPE feature SYSTEM \"feature.dtd\">";
				String xmlFeatureMain = new String(xmlFeatureIs.readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);

				boolean validateXMLWithDTD = validateXMLWithDTD(doctype + "\n" + xmlFeatureMain,
					P2PublisherTest.class.getResourceAsStream("feature.dtd"));
				assertTrue("P2 bndtools.main.feature/feature.xml is invalid", validateXMLWithDTD);

				String expectedMain = Files.readString(p.getFile("expected-feature.main.xml")
					.toPath());
				assertEquals("feature.xml does not match expected-feature.main.xml", normalizeXml(expectedMain),
					normalizeXml(xmlFeatureMain));

				InputStream xmlFeaturePdeIs = featurePde.getResource("feature.xml")
					.openInputStream();
				String xmlFeaturePde = new String(xmlFeaturePdeIs.readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);

				String expectedPde = Files.readString(p.getFile("expected-feature.pde.xml")
					.toPath());
				assertEquals("feature.xml does not match expected-feature.xml", expectedPde, xmlFeaturePde);
			} finally {}
		}
	}

	@Test
	void testBundleRequirementVersionRanges() throws Exception {
		String resolvedVersion = "2.0.9";
		assertThat(P2Export.toRequirementRange(resolvedVersion, null)).isEqualTo("[2.0.9,2.0.9]");
		assertThat(P2Export.toRequirementRange(resolvedVersion, resolvedVersion)).isEqualTo("[2.0.9,2.0.9]");
		assertThat(P2Export.toRequirementRange(resolvedVersion, "[2.0.0,2.1.0]")).isEqualTo("[2.0.0,2.1.0]");

		try (Processor processor = new Processor()) {
			String consumerRange = processor.getReplacer()
				.process("${range;[===,+00);2.0.9}");
			String providerRange = processor.getReplacer()
				.process("${range;[===,=+0);2.0.9}");

			assertThat(P2Export.toRequirementRange(resolvedVersion, consumerRange))
					.isEqualTo("[2.0.9,3.0.0)");
			assertThat(P2Export.toRequirementRange(resolvedVersion, providerRange))
					.isEqualTo("[2.0.9,2.1.0)");
		}
	}

	@Test
	public void testExisting() throws FileNotFoundException {
		File file = IO.getFile("testdata/p2-publish/existing/previous-content.xml");
		assertTrue("P2 previous previous-content.xml is invalid",
			validateXML(new FileInputStream(file), P2PublisherTest.class.getResourceAsStream("content-schema.xsd")));
	}

	public static boolean validateXML(InputStream xmlStream, InputStream xsdStream) {
		try {
			SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			Schema schema = factory.newSchema(new StreamSource(xsdStream));
			Validator validator = schema.newValidator();
			validator.validate(new StreamSource(xmlStream));
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	private static String normalizeXml(String xml) {
		return xml.replaceAll("\\r\\n", "\n")
			.replaceAll("[ \\t]+(?=\\n)", "")
			.replaceAll("(?m)^[ \\t]+(?=<)", "");
	}

	/**
	 * Validate an XML against a DTD. Source of DTD:
	 * https://help.eclipse.org/latest/index.jsp?topic=%2Forg.eclipse.platform.doc.isv%2Freference%2Fmisc%2Ffeature_manifest.html
	 */
	public static boolean validateXMLWithDTD(String xml, InputStream dtdStream) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setNamespaceAware(false);
            factory.setValidating(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            SAXParser parser = factory.newSAXParser();
            XMLReader reader = parser.getXMLReader();

            reader.setEntityResolver((publicId, systemId) -> {
                InputSource is = new InputSource(dtdStream);
                is.setPublicId(publicId);
                is.setSystemId(systemId != null ? systemId : "in-memory.dtd");
                return is;
            });

			final boolean[] ok = {
				true
			};
			reader.setErrorHandler(new DefaultHandler() {
				@Override
				public void warning(SAXParseException e) {}

				@Override
				public void error(SAXParseException e) {
					e.printStackTrace();
					ok[0] = false;
				}

				@Override
				public void fatalError(SAXParseException e) {
					e.printStackTrace();
					ok[0] = false;
				}
			});

			reader.parse(new InputSource(new StringReader(xml)));
			return ok[0];
        } catch (Exception e) {
            return false;
        }
    }




}
