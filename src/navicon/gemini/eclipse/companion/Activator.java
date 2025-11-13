package navicon.gemini.eclipse.companion;

import org.osgi.framework.BundleContext;

import org.osgi.framework.BundleActivator;

public class Activator implements BundleActivator {

	// Make the server instance public and static so the Startup class can access it
	public static GeminiHttpServer server;
	private static BundleContext context;

	static BundleContext getContext() {
		return context;
	}

	public void start(BundleContext bundleContext) throws Exception {
		Activator.context = bundleContext;
		// The startup logic is now handled by the Startup class via the extension
		// point.
		// This method can be empty.
	}

	public void stop(BundleContext bundleContext) throws Exception {
		Activator.context = null;
		// The stop logic remains here.
		if (server != null) {
			server.stop();
		}
	}
}