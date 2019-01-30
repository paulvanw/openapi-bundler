package com.networknt.openapi;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.yaml.snakeyaml.Yaml;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import com.networknt.oas.OpenApiParser;
import com.networknt.oas.model.OpenApi3;

/**
 * Class resolves all externalized references from within an OpenAPI definition
 * file and bundles the output into a single OpenAPI file, in JSON or YAML
 * format
 * 
 * @author ddobrin
 */
public class Bundler {

	public static ObjectMapper mapper = new ObjectMapper();

	static Map<String, Map<String, Object>> references = new ConcurrentHashMap<>();
	static String folder = null;
	static Map<String, Object> definitions = null;

	@Parameter(description = "operation: The operation to be performed. Supported operations: bundle | validate. Must be specified")
	String operation;

	@Parameter(names = { "--dir",
			"-d" }, required = true, description = "The input directory where the YAML files can be found for bundling | validation. Mandatory parameter.")
	String dir;

	@Parameter(names = { "--file",
			"-f" }, description = "The name of the YAML file to be bundled or validated. Default: openapi.yaml")
	String file;

	@Parameter(names = { "--outputFormat",
			"-o" }, description = "The output format for the bundled file: YAML | JSON | both. Default: YAML")
	String output = "yaml";

	@Parameter(names = { "--outputFile",
	"-of" }, description = "The name of the bundled and validated OpenAPI file. Default: openapi.bundled")
	String outputFile = "openapi.bundled";

	@Parameter(names = { "--outputDir",
	"-od" }, description = "The output directory of the bundled and validated file. Default: same as input directory specified in <dir>")
	String outputDir;

	@Parameter(names = "-debug", description = "Debug mode")
	private static boolean debug = false;

	@Parameter(names = { "--help", "-h" }, help = true)
	private boolean help;

	public static void main(String... argv) {
		try {
			//parse the incoming arguments 
			// supported operation: 
			// - bundle
			// - validate
			Bundler bundler = new Bundler();
			JCommander jCommander = JCommander.newBuilder().addObject(bundler).build();
			jCommander.parse(argv);
			bundler.run(jCommander);
		} catch (ParameterException e) {
			System.out.println("Error while parsing command-line parameters: " + e.getLocalizedMessage());
			e.usage();
		}
	}

	public void run(JCommander jCommander) {
		// check if help must be displayed
		if (help) {
			jCommander.usage();
			return;
		}

		// first mandatory argument is the folder where the YAML files to be bundled are to be found
		// second argument is optional; allows the setting of an input file name; openapi.yaml is the default
		if (dir != null) {
			folder = dir;
			// The input parameter is the folder that contains openapi.yaml and
			// this folder will be the base path to calculate remote references.
			// if the second argument is a different file name, it will be used
			// otherwise, default is "openapi.yaml"
			String fileName = file == null ? "openapi.yaml" : file;
			
			// if the operation is validate, validate the file, in YAML or JSON format, then exit the process
			validateSpecification(folder, fileName);
			
			if (operation.equalsIgnoreCase("validate")) 
				return;

			// set output directory. 
			// if not set, default it to the input <dir>
			if(outputDir == null)
				outputDir = folder;
			
			// bundle the file and validate the resulting file
			System.out.println(
					String.format("OpenAPI Bundler: Bundling API definition with file name <%s>, from directory <%s>",
							fileName, folder));

			Path path = Paths.get(folder, fileName);
			try (InputStream is = Files.newInputStream(path)) {
				String json = null;
				Yaml yaml = new Yaml();
				Map<String, Object> map = (Map<String, Object>) yaml.load(is);

				// we have to handle components as a separate map, otherwise, we will have
				// concurrent access exception while iterating the map and updating components.
				definitions = new HashMap<>();

				Map<String, Object> components = (Map<String, Object>) map.get("components");
				if ((components != null) && (components.get("schemas") != null)) {
					definitions.putAll((Map<String, Object>) components.get("schemas"));
				}

				// now let's handle the references.
				resolveMap(map);
				// now the definitions might contains some references that are not in
				// definitions.
				Map<String, Object> def = new HashMap<>(definitions);
				if (debug)
					System.out.println("Start resolving components for the first time ...");
				resolveMap(def);

				def = new HashMap<>(definitions);
				if (debug)
					System.out.println("Start resolving components for the second time ...");
				resolveMap(def);

				def = new HashMap<>(definitions);
				if (debug)
					System.out.println("Start resolving components for the third time ...");
				resolveMap(def);

				// add the resolved components to the main map, before persisting
				Map<String, Object> schemasMap = null;
				Map<String, Object> componentsMap = (Map<String, Object>) map.get("components");
				if ((componentsMap != null) && (componentsMap.get("schemas") != null)) {
					schemasMap = (Map<String, Object>) componentsMap.get("schemas");
				} else {
					if (componentsMap==null) {
						componentsMap = new HashMap<String, Object>();
						map.put("components", componentsMap);
						schemasMap = new HashMap<String, Object>();
						componentsMap.put("schemas", schemasMap);
					} else if (componentsMap.get("schemas")==null) {
						schemasMap = new HashMap<String, Object>();
						componentsMap.put("schemas", schemasMap);
					}
				}

				schemasMap.putAll(definitions);

				// Convert the map back to JSON and serialize it.
				if (output.equalsIgnoreCase("json") || output.equalsIgnoreCase("both")) {
					if (debug)
						System.out.println(
								String.format("OpenAPI Bundler: write bundled JSON file to %s ... in directory %s", outputFile, outputDir));
					json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);

					// write the output to openapi.json
					Files.write(Paths.get(outputDir, String.format("%s.%s", outputFile, "json")), json.getBytes());
					
					// validate the output file
					validateSpecification(outputDir, String.format("%s.%s", outputFile, "json"));
				}

				// Convert the map back to YAML and serialize it.
				if (output.equalsIgnoreCase("yaml") || output.equalsIgnoreCase("both")) {
					if (debug)
						System.out.println(
								String.format("OpenAPI Bundler: write bundled YAML file to %s ... in directory %s", outputFile, outputDir));
					YAMLFactory yamlFactory = new YAMLFactory();
					yamlFactory.enable(Feature.MINIMIZE_QUOTES);
					yamlFactory.disable(Feature.SPLIT_LINES);
					yamlFactory.disable(Feature.WRITE_DOC_START_MARKER);
					yamlFactory.disable(Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS);
					yamlFactory.disable(Feature.LITERAL_BLOCK_STYLE);

					ObjectMapper objMapper = new ObjectMapper(yamlFactory);
					String yamlOutput = objMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
					Files.write(Paths.get(outputDir, String.format("%s.%s", outputFile, "yaml")), yamlOutput.getBytes());
					
					// validate the output file
					validateSpecification(outputDir, String.format("%s.%s", outputFile, "yaml"));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			System.out.println("OpenAPI Bundler: ERROR: You must pass in a folder to a yaml file!");
		}

		// completed bundling
		output = output.equalsIgnoreCase("both") ? "YAML & JSON" : output.toUpperCase();
		System.out.println(String.format(
				"OpenAPI Bundler: Bundling API definition has completed. Output directory <%s>, in file format %s", dir,
				output));
	}

	private static void validateSpecification(String dir, String fileName) {
		try {
			@SuppressWarnings("unused")
			OpenApi3 model = (OpenApi3) new OpenApiParser().parse(new File(dir + "/" + fileName), true);

			System.out.println(String.format("OpenAPI3 Validation: Definition file <%s> in directory <%s> is valid ....", fileName, dir));
		} catch (Exception e) {
			System.out.println(
					String.format("OpenAPI3 Validation: Definition file <%s> in directory <%s> failed with exception %s", fileName, dir, e));
		}
	}

	private static Map<String, Object> handlerPointer(String pointer) {
		Map<String, Object> result = new HashMap<>();
		if (pointer.startsWith("#")) {
			// There are two cases with local reference. 1, original in
			// local reference and it has path of "definitions" or 2, local reference
			// that extracted from reference file with reference to an object directly.
			String refKey = pointer.substring(pointer.lastIndexOf("/") + 1);

			if (debug)
				System.out.println("refKey = " + refKey);

			if (pointer.contains("components")) {
				// if the $ref is an object, keep it that way and if $ref is not an object, make
				// it inline
				// and remove it from definitions.
				Map<String, Object> refMap = (Map<String, Object>) definitions.get(refKey);
				if (refMap == null) {
					System.out.println("ERROR: Could not find reference in definitions for key " + refKey);
					System.exit(0);
				}
				if (isRefMapObject(refMap)) {
					result.put("$ref", pointer);
				} else {
					result = refMap;
				}
			} else {
				// This is something extracted from extenal file and the reference is still
				// local.
				// need to look up for all reference files in order to find it.
				Map<String, Object> refMap = null;
				for (Map<String, Object> r : references.values()) {
					refMap = (Map<String, Object>) r.get(refKey);
					if (refMap != null)
						break;
				}

				if (refMap == null) {
					System.out.println("ERROR: Could not resolve reference locally in components for key " + refKey
							+ ". Please check your components section.");
					System.exit(0);
				}
				if (isRefMapObject(refMap)) {
					definitions.put(refKey, refMap);
					result.put("$ref", "#/components/schemas/" + refKey);
				} else {
					result = refMap;
				}
			}
		} else {
			// external reference and it must be a relative url
			Map<String, Object> refs = loadRef(pointer.substring(0, pointer.indexOf("#")));
			String refKey = pointer.substring(pointer.indexOf("#/") + 2);
			// System.out.println("refKey = " + refKey);
			Map<String, Object> refMap = (Map<String, Object>) refs.get(refKey);
			// now need to resolve the internal references in refMap.
			if (refMap == null) {
				System.out.println("ERROR: Could not find reference in external file for pointer " + pointer);
				System.exit(0);
			}
			// check if the refMap type is object or not.
			if (isRefMapObject(refMap)) {
				// add to definitions
				definitions.put(refKey, refMap);
				// update the ref pointer to local
				result.put("$ref", "#/components/schemas/" + refKey);
			} else {
				// simple type, inline refMap instead.
				resolveMap(refMap);
				result = refMap;
			}
		}
		return result;
	}

	/**
	 * Check if the input map is an json object or not.
	 * 
	 * @param refMap
	 *            input map
	 * @return
	 */
	private static boolean isRefMapObject(Map<String, Object> refMap) {
		boolean result = false;
		for (Map.Entry<String, Object> entry : refMap.entrySet()) {
			if ("type".equals(String.valueOf(entry.getKey())) && "object".equals(String.valueOf(entry.getValue()))) {
				result = true;
			}
		}
		return result;
	}

	/**
	 * load and cache remote reference. folder is a static variable assigned by
	 * argv[0] it will check the cache first and only load it if it doesn't exist in
	 * cache.
	 *
	 * @param path
	 *            the path of remote file
	 * @return map of remote references
	 */
	private static Map<String, Object> loadRef(String path) {
		Map<String, Object> result = references.get(path);
		if (result == null) {
			Path p = Paths.get(folder, path);
			try (InputStream is = Files.newInputStream(p)) {
				Yaml yaml = new Yaml();
				result = (Map<String, Object>) yaml.load(is);
				references.put(path, result);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return result;
	}

	/**
	 * It deep iterate a map object and looking for "$ref" and handle it.
	 * 
	 * @param map
	 *            the map of openapi.yaml
	 */
	public static void resolveMap(Map<String, Object> map) {
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			String key = String.valueOf(entry.getKey());
			Object value = entry.getValue();
			if (debug) {
				System.out.println("resolveMap key = " + key + " value = " + value);
			}
			if (value instanceof Map) {
				// check if this map is $ref, it should be size = 1
				if (((Map) value).size() == 1) {
					Set keys = ((Map) value).keySet();
					for (Iterator i = keys.iterator(); i.hasNext();) {
						String k = (String) i.next();
						if ("$ref".equals(k)) {
							String pointer = (String) ((Map) value).get(k);
							if (debug)
								System.out.println("pointer = " + pointer);
							Map refMap = handlerPointer(pointer);
							entry.setValue(refMap);
						}
					}
				}
				resolveMap((Map) value);
			} else if (value instanceof List) {
				resolveList((List) value);
			} else {
				continue;
			}
		}
	}

	public static void resolveList(List list) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) instanceof Map) {
				// check if this map is $ref
				if (((Map) list.get(i)).size() == 1) {
					Set keys = ((Map) list.get(i)).keySet();
					for (Iterator j = keys.iterator(); j.hasNext();) {
						String k = (String) j.next();
						if ("$ref".equals(k)) {
							String pointer = (String) ((Map) list.get(i)).get(k);
							list.set(i, handlerPointer(pointer));
						}
					}
				}
				resolveMap((Map<String, Object>) list.get(i));
			} else if (list.get(i) instanceof List) {
				resolveList((List) list.get(i));
			} else {
				continue;
			}
		}
	}

}
