package navicon.gemini.eclipse.companion;

import org.eclipse.ui.IStartup;

public class Startup implements IStartup {

    @Override
    public void earlyStartup() {
        // The server needs to run in a background thread
        // to avoid blocking the Eclipse UI thread.
        Thread serverThread = new Thread(() -> {
            // Set the context class loader to prevent class loading issues
            Thread.currentThread().setContextClassLoader(Startup.class.getClassLoader());
            
            // Get the server instance from the Activator and start it
            Activator.server = new GeminiHttpServer();
            Activator.server.start();
        });
        
        serverThread.setName("Gemini-Companion-Server-Thread");
        serverThread.setDaemon(true); // Ensures the thread doesn't prevent Eclipse from shutting down
        serverThread.start();
    }
}