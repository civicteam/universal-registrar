package uniregistrar.local;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import uniregistrar.RegistrationException;
import uniregistrar.UniRegistrar;
import uniregistrar.driver.Driver;
import uniregistrar.driver.http.HttpDriver;
import uniregistrar.local.extensions.Extension;
import uniregistrar.local.extensions.ExtensionStatus;
import uniregistrar.request.DeactivateRequest;
import uniregistrar.request.CreateRequest;
import uniregistrar.request.UpdateRequest;
import uniregistrar.state.DeactivateState;
import uniregistrar.state.CreateState;
import uniregistrar.state.UpdateState;

public class LocalUniRegistrar implements UniRegistrar {

	private static Logger log = LoggerFactory.getLogger(LocalUniRegistrar.class);

	private Map<String, Driver> drivers = new LinkedHashMap<String, Driver> ();
	private List<Extension> extensions = new ArrayList<Extension> ();

	public LocalUniRegistrar() {

	}

	public static LocalUniRegistrar fromConfigFile(String filePath) throws FileNotFoundException, IOException {

		final Gson gson = new Gson();

		Map<String, Driver> drivers = new LinkedHashMap<String, Driver> ();

		try (Reader reader = new FileReader(new File(filePath))) {

			JsonObject jsonObjectRoot  = gson.fromJson(reader, JsonObject.class);
			JsonArray jsonArrayDrivers = jsonObjectRoot.getAsJsonArray("drivers");

			int i = 0;

			for (Iterator<JsonElement> jsonElementsDrivers = jsonArrayDrivers.iterator(); jsonElementsDrivers.hasNext(); ) {

				i++;

				JsonObject jsonObjectDriver = (JsonObject) jsonElementsDrivers.next();

				String method = jsonObjectDriver.has("method") ? jsonObjectDriver.get("method").getAsString() : null;
				String url = jsonObjectDriver.has("url") ? jsonObjectDriver.get("url").getAsString() : null;
				String propertiesEndpoint = jsonObjectDriver.has("propertiesEndpoint") ? jsonObjectDriver.get("propertiesEndpoint").getAsString() : null;

				if (method == null) throw new IllegalArgumentException("Missing 'method' entry in driver configuration.");
				if (url == null) throw new IllegalArgumentException("Missing 'url' entry in driver configuration.");

				// construct HTTP driver

				HttpDriver driver = new HttpDriver();

				if (! url.endsWith("/")) url = url + "/";

				driver.setCreateUri(url + "1.0/create");
				driver.setUpdateUri(url + "1.0/update");
				driver.setDeactivateUri(url + "1.0/deactivate");
				if ("true".equals(propertiesEndpoint)) driver.setPropertiesUri(url + "1.0/properties");

				// done

				drivers.put(method, driver);
				if (log.isInfoEnabled()) log.info("Added driver for method '" + method + "' at " + driver.getCreateUri() + " and " + driver.getUpdateUri() + " and " + driver.getDeactivateUri() + " (" + driver.getPropertiesUri() + ")");
			}
		}

		LocalUniRegistrar localUniRegistrar = new LocalUniRegistrar();
		localUniRegistrar.setDrivers(drivers);

		return localUniRegistrar;
	}

	@Override
	public CreateState create(String method, CreateRequest createRequest) throws RegistrationException {

		if (method == null) throw new NullPointerException();
		if (createRequest == null) throw new NullPointerException();

		if (this.getDrivers() == null) throw new RegistrationException("No drivers configured.");

		// start time

		long start = System.currentTimeMillis();

		// prepare create state

		CreateState createState = CreateState.build();
		ExtensionStatus extensionStatus = new ExtensionStatus();

		// [before create]

		if (! extensionStatus.skipExtensionsBefore()) {
			for (Extension extension : this.getExtensions()) {
				extensionStatus.or(extension.beforeCreate(method, createRequest, createState, this));
				if (extensionStatus.skipExtensionsBefore()) break;
			}
		}

		// [create]

		if (! extensionStatus.skipDriver()) {

			Driver driver = this.getDrivers().get(method);
			if (driver == null) throw new RegistrationException("Unsupported method: " + method);
			if (log.isDebugEnabled()) log.debug("Attempting to create " + createRequest + " with driver " + driver.getClass().getSimpleName());

			CreateState driverCreateState = driver.create(createRequest);

			if (driverCreateState != null) {

				createState.setJobId(driverCreateState.getJobId());
				createState.setDidState(driverCreateState.getDidState());
				createState.setDidDocumentMetadata(driverCreateState.getDidDocumentMetadata());
			}

			createState.getDidRegistrationMetadata().put("method", method);
		}

		// [after create]

		if (! extensionStatus.skipExtensionsAfter()) {
			for (Extension extension : this.getExtensions()) {
				extensionStatus.or(extension.afterCreate(method, createRequest, createState, this));
				if (extensionStatus.skipExtensionsAfter()) break;
			}
		}

		// additional metadata

		long stop = System.currentTimeMillis();
		createState.getDidRegistrationMetadata().put("duration", Long.valueOf(stop - start));

		// done

		return createState;
	}

	@Override
	public UpdateState update(String method, UpdateRequest updateRequest) throws RegistrationException {

		if (method == null) throw new NullPointerException();
		if (updateRequest == null) throw new NullPointerException();

		if (this.getDrivers() == null) throw new RegistrationException("No drivers configured.");

		// start time

		long start = System.currentTimeMillis();

		// prepare update state

		UpdateState updateState = UpdateState.build();
		ExtensionStatus extensionStatus = new ExtensionStatus();

		// [before update]

		if (! extensionStatus.skipExtensionsBefore()) {
			for (Extension extension : this.getExtensions()) {
				extensionStatus.or(extension.beforeUpdate(method, updateRequest, updateState, this));
				if (extensionStatus.skipExtensionsBefore()) break;
			}
		}

		// [update]

		if (! extensionStatus.skipDriver()) {

			Driver driver = this.getDrivers().get(method);
			if (driver == null) throw new RegistrationException("Unsupported method: " + method);
			if (log.isDebugEnabled()) log.debug("Attempting to update " + updateRequest + " with driver " + driver.getClass().getSimpleName());

			UpdateState driverUpdateState = driver.update(updateRequest);
			updateState.setJobId(driverUpdateState.getJobId());
			updateState.setDidState(driverUpdateState.getDidState());
			updateState.setDidDocumentMetadata(driverUpdateState.getDidDocumentMetadata());

			updateState.getDidRegistrationMetadata().put("method", method);
		}

		// [after update]

		if (! extensionStatus.skipExtensionsAfter()) {
			for (Extension extension : this.getExtensions()) {
				extensionStatus.or(extension.afterUpdate(method, updateRequest, updateState, this));
				if (extensionStatus.skipExtensionsAfter()) break;
			}
		}

		// additional metadata

		long stop = System.currentTimeMillis();
		updateState.getDidRegistrationMetadata().put("duration", Long.valueOf(stop - start));

		// done

		return updateState;
	}

	@Override
	public DeactivateState deactivate(String method, DeactivateRequest deactivateRequest) throws RegistrationException {

		if (method == null) throw new NullPointerException();
		if (deactivateRequest == null) throw new NullPointerException();

		if (this.getDrivers() == null) throw new RegistrationException("No drivers configured.");

		// start time

		long start = System.currentTimeMillis();

		// prepare deactivate state

		DeactivateState deactivateState = DeactivateState.build();
		ExtensionStatus extensionStatus = new ExtensionStatus();

		// [before deactivate]

		if (! extensionStatus.skipExtensionsBefore()) {
			for (Extension extension : this.getExtensions()) {
				extensionStatus.or(extension.beforeDeactivate(method, deactivateRequest, deactivateState, this));
				if (extensionStatus.skipExtensionsBefore()) break;
			}
		}

		// [deactivate]

		if (! extensionStatus.skipDriver()) {

			Driver driver = this.getDrivers().get(method);
			if (driver == null) throw new RegistrationException("Unsupported method: " + method);
			if (log.isDebugEnabled()) log.debug("Attempting to deactivate " + deactivateRequest + " with driver " + driver.getClass().getSimpleName());

			DeactivateState driverDeactivateState = driver.deactivate(deactivateRequest);
			deactivateState.setJobId(driverDeactivateState.getJobId());
			deactivateState.setDidState(driverDeactivateState.getDidState());
			deactivateState.setDidDocumentMetadata(driverDeactivateState.getDidDocumentMetadata());

			deactivateState.getDidRegistrationMetadata().put("method", method);
		}

		// [after deactivate]

		if (! extensionStatus.skipExtensionsAfter()) {
			for (Extension extension : this.getExtensions()) {
				extensionStatus.or(extension.afterDeactivate(method, deactivateRequest, deactivateState, this));
				if (extensionStatus.skipExtensionsAfter()) break;
			}
		}

		// additional metadata

		long stop = System.currentTimeMillis();
		deactivateState.getDidRegistrationMetadata().put("duration", Long.valueOf(stop - start));

		// done

		return deactivateState;
	}

	@Override
	public Map<String, Map<String, Object>> properties() throws RegistrationException {

		if (this.getDrivers() == null) throw new RegistrationException("No drivers configured.");

		Map<String, Map<String, Object>> properties = new LinkedHashMap<String, Map<String, Object>> ();

		for (Entry<String, Driver> driver : this.getDrivers().entrySet()) {

			if (log.isDebugEnabled()) log.debug("Loading properties for driver " + driver.getKey() + " (" + driver.getValue().getClass().getSimpleName() + ")");

			Map<String, Object> driverProperties = driver.getValue().properties();
			if (driverProperties == null) driverProperties = Collections.emptyMap();

			properties.put(driver.getKey(), driverProperties);
		}

		// done

		if (log.isDebugEnabled()) log.debug("Loaded properties: " + properties);
		return properties;
	}

	@Override
	public Set<String> methods() throws RegistrationException {

		if (this.getDrivers() == null) throw new RegistrationException("No drivers configured.");

		Set<String> methods = this.getDrivers().keySet();

		// done

		if (log.isDebugEnabled()) log.debug("Loaded methods: " + methods);
		return methods;
	}

	/*
	 * Getters and setters
	 */

	public Map<String, Driver> getDrivers() {
		return this.drivers;
	}

	@SuppressWarnings("unchecked")
	public <T extends Driver> T getDriver(Class<T> driverClass) {
		for (Driver driver : this.getDrivers().values()) {
			if (driverClass.isAssignableFrom(driver.getClass())) return (T) driver;
		}
		return null;
	}

	public void setDrivers(Map<String, Driver> drivers) {
		this.drivers = drivers;
	}

	public List<Extension> getExtensions() {
		return this.extensions;
	}

	public void setExtensions(List<Extension> extensions) {
		this.extensions = extensions;
	}
}
