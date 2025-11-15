package navicon.gemini.eclipse.companion;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.BundleContext;

import org.osgi.framework.BundleActivator;

public class Activator implements BundleActivator {

	// Make the server instance public and static so the Startup class can access it
	public static GeminiHttpServer server;
	private static BundleContext context;
	public static final String PLUGIN_ID = "navicon.gemini.eclipse.companion";

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
	
	public static void logInfo(String message) {
        if (context != null && context.getBundle() != null) {
            IStatus status = new Status(IStatus.INFO, PLUGIN_ID, message);
            context.getBundle().adapt(org.eclipse.osgi.service.debug.DebugOptions.class);
            org.eclipse.core.runtime.Platform.getLog(context.getBundle()).log(status);
        } else {
            System.out.println(message);
        }
    }
}