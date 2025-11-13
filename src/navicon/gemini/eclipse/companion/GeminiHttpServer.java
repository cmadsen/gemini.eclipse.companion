package navicon.gemini.eclipse.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class GeminiHttpServer implements IPartListener2, ISelectionListener {

	private HttpServer server;
	private Path discoveryFilePath;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final String authToken = UUID.randomUUID().toString();
	private volatile HttpExchange mcpExchange;
	private final AtomicBoolean sseSessionActive = new AtomicBoolean(false);

	public void start() {
		try {
			server = HttpServer.create(new InetSocketAddress(0), 0);
			int port = server.getAddress().getPort();
			server.createContext("/mcp", this::handleMcpConnection);
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();
			System.out.println("Gemini Companion Server started on port: " + port);
			createDiscoveryFile(port);
			updateEnvironmentFile(port);
			startListening();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void stop() {
		stopListening();
		if (server != null) {
			server.stop(0);
		}
		deleteDiscoveryFile();
		deleteEnvironmentFile();
		sseSessionActive.set(false);
		if (mcpExchange != null) {
			mcpExchange.close();
			mcpExchange = null;
		}
	}

	private void updateEnvironmentFile(int port) {
		try {
			IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
			String workspacePath = workspaceRoot.getLocation().toOSString();
			String content = String.format("export GEMINI_CLI_IDE_SERVER_PORT=%d\n" + "export TERM_PROGRAM=vscode\n"
					+ "export GEMINI_CLI_IDE_WORKSPACE_PATH=\"%s\"\n", port, workspacePath);
			Path geminiDir = Paths.get(System.getProperty("java.io.tmpdir"), "gemini");
			Files.createDirectories(geminiDir);
			Path envFile = geminiDir.resolve("eclipse_env.sh");
			Files.writeString(envFile, content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void deleteEnvironmentFile() {
		try {
			Path envFile = Paths.get(System.getProperty("java.io.tmpdir"), "gemini", "eclipse_env.sh");
			if (Files.exists(envFile)) {
				Files.delete(envFile);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// --- MCP Connection Handling ---

	private void handleMcpConnection(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			return;
		}

		String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		JsonNode request;
		try {
			request = objectMapper.readTree(requestBody);
		} catch (Exception e) {
			sendJsonRpcError(exchange, null, -32700, "Parse error");
			return;
		}

		String method = request.has("method") ? request.get("method").asText() : null;
		JsonNode id = request.has("id") ? request.get("id") : null;

		if ("initialize".equals(method)) {
			handleInitializeRequest(exchange, id);
		} else if ("notifications/initialized".equals(method)) {
			handleInitializedNotification(exchange);
		} else if ("tools/list".equals(method)) {
			handleToolsList(exchange, id);
		} else if ("tools/call".equals(method)) {
			handleToolCall(exchange, request);
		} else {
			sendJsonRpcError(exchange, id, -32601, "Method not found: " + method);
		}
	}

	private void handleInitializeRequest(HttpExchange exchange, JsonNode id) throws IOException {
		if (sseSessionActive.get()) {
			sendJsonRpcError(exchange, id, -32600, "Session already active");
			return;
		}

		sseSessionActive.set(true);
		this.mcpExchange = exchange;

		exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().add("Connection", "keep-alive");
		exchange.getResponseHeaders().add("Cache-Control", "no-cache");
		exchange.getResponseHeaders().add("X-Accel-Buffering", "no");
		exchange.sendResponseHeaders(200, 0);

		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", id);
		ObjectNode result = objectMapper.createObjectNode();
		result.put("protocolVersion", "2025-06-18");
		ObjectNode capabilities = objectMapper.createObjectNode();
		ObjectNode tools = objectMapper.createObjectNode();
		tools.put("listChanged", false);
		capabilities.set("tools", tools);
		result.set("capabilities", capabilities);
		ObjectNode serverInfo = objectMapper.createObjectNode();
		serverInfo.put("name", "eclipse-companion");
		serverInfo.put("version", "1.0.0");
		result.set("serverInfo", serverInfo);
		response.set("result", result);

		sendSseMessage(exchange.getResponseBody(), response);
		sendInitialContext();
		startKeepAliveThread(exchange, exchange.getResponseBody());
	}

	private void handleInitializedNotification(HttpExchange exchange) throws IOException {
		exchange.sendResponseHeaders(200, -1);
		exchange.close();
	}

	// --- Tool Handling ---

	private void handleToolsList(HttpExchange exchange, JsonNode id) throws IOException {
		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", id);
		ArrayNode tools = objectMapper.createArrayNode();

		// openDiff tool
		ObjectNode openDiffTool = objectMapper.createObjectNode();
		openDiffTool.put("name", "openDiff");
		openDiffTool.put("description", "Opens a diff view in Eclipse comparing original and new content");
		ObjectNode openDiffSchema = objectMapper.createObjectNode();
		openDiffSchema.put("type", "object");
		ObjectNode openDiffProps = objectMapper.createObjectNode();
		ObjectNode filePathProp = objectMapper.createObjectNode();
		filePathProp.put("type", "string");
		openDiffProps.set("filePath", filePathProp);
		ObjectNode newContentProp = objectMapper.createObjectNode();
		newContentProp.put("type", "string");
		openDiffProps.set("newContent", newContentProp);
		openDiffSchema.set("properties", openDiffProps);
		ArrayNode openDiffRequired = objectMapper.createArrayNode();
		openDiffRequired.add("filePath");
		openDiffRequired.add("newContent");
		openDiffSchema.set("required", openDiffRequired);
		openDiffTool.set("inputSchema", openDiffSchema);
		tools.add(openDiffTool);

		// closeDiff tool
		ObjectNode closeDiffTool = objectMapper.createObjectNode();
		closeDiffTool.put("name", "closeDiff");
		closeDiffTool.put("description", "Closes an open diff view.");
		ObjectNode closeDiffSchema = objectMapper.createObjectNode();
		closeDiffSchema.put("type", "object");
		closeDiffSchema.set("properties", objectMapper.createObjectNode());
		closeDiffTool.set("inputSchema", closeDiffSchema);
		tools.add(closeDiffTool);

		ObjectNode result = objectMapper.createObjectNode();
		result.set("tools", tools);
		response.set("result", result);
		sendJsonResponse(exchange, response);
	}

	private void handleToolCall(HttpExchange exchange, JsonNode request) throws IOException {
		JsonNode params = request.get("params");
		JsonNode id = request.get("id");
		String toolName = params.get("name").asText();
		JsonNode arguments = params.get("arguments");
		if ("openDiff".equals(toolName)) {
			String filePath = arguments.get("filePath").asText();
			String newContent = arguments.get("newContent").asText();
			Display.getDefault().asyncExec(() -> showDiffView(filePath, newContent));
			sendToolSuccessResponse(exchange, id, "Diff view opened successfully.");
		} else if ("closeDiff".equals(toolName)) {
			// TODO: Implement logic to programmatically close the diff view.
			sendToolSuccessResponse(exchange, id, "Diff view closed (not implemented).");
		} else {
			sendJsonRpcError(exchange, id, -32601, "Unknown tool: " + toolName);
		}
	}

	// --- IDE Context Listening & Reporting ---

	private void startListening() {
		Display.getDefault().asyncExec(() -> {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null) {
				window.getPartService().addPartListener(this);
				window.getSelectionService().addSelectionListener(this);
			}
		});
	}

	private void stopListening() {
		Display.getDefault().asyncExec(() -> {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null) {
				window.getPartService().removePartListener(this);
				window.getSelectionService().removeSelectionListener(this);
			}
		});
	}

	private void sendInitialContext() {
		updateAndSendIdeContext();
	}

	private void updateAndSendIdeContext() {
		Display.getDefault().asyncExec(() -> {
			IdeContext context = new IdeContext();
			IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
			IProject[] projects = workspaceRoot.getProjects();
			String pathSeparator = System.getProperty("path.separator");
			context.workspacePath = Arrays.stream(projects).filter(IProject::isOpen)
					.map(p -> p.getLocation().toOSString()).collect(Collectors.joining(pathSeparator));
			if (context.workspacePath.isEmpty()) {
				context.workspacePath = workspaceRoot.getLocation().toOSString();
			}

			context.openFiles = new ArrayList<>();
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null) {
				IWorkbenchPage page = window.getActivePage();
				if (page != null) {
					IEditorPart activeEditor = page.getActiveEditor();
					for (IEditorReference editorRef : page.getEditorReferences()) {
						try {
							IEditorInput editorInput = editorRef.getEditorInput();
							IFile file = editorInput.getAdapter(IFile.class);
							if (file != null) {
								OpenFile openFile = new OpenFile();
								openFile.filePath = file.getLocation().toOSString();
								openFile.timestamp = file.getLocalTimeStamp();
								openFile.active = editorRef.getPart(false) == activeEditor;
								context.openFiles.add(openFile);
							}
						} catch (Exception e) {
							// Ignore editors that don't resolve to files
						}
					}

					if (activeEditor instanceof ITextEditor) {
						ITextEditor textEditor = (ITextEditor) activeEditor;
						IFile file = textEditor.getEditorInput().getAdapter(IFile.class);
						if (file != null) {
							context.activeFile = file.getLocation().toOSString();
						}

						ISelection selection = textEditor.getSelectionProvider().getSelection();
						if (selection instanceof ITextSelection) {
							ITextSelection textSelection = (ITextSelection) selection;
							context.selectedText = textSelection.getText();

							IDocument document = textEditor.getDocumentProvider()
									.getDocument(textEditor.getEditorInput());
							if (document != null) {
								try {
									context.cursorPosition = new CursorPosition();
									context.cursorPosition.line = document.getLineOfOffset(textSelection.getOffset());
									context.cursorPosition.character = textSelection.getOffset()
											- document.getLineOffset(context.cursorPosition.line);
								} catch (Exception e) {
									// Ignore if offset is invalid
								}
							}
						}
					}
				}
			}
			sendMcpNotification("ide/contextUpdate", context);
		});
	}

	@Override
	public void selectionChanged(IWorkbenchPart part, ISelection selection) {
		if (part instanceof IEditorPart) {
			updateAndSendIdeContext();
		}
	}

	@Override
	public void partActivated(IWorkbenchPartReference partRef) {
		updateAndSendIdeContext();
	}

	@Override
	public void partBroughtToTop(IWorkbenchPartReference partRef) {
		updateAndSendIdeContext();
	}

	@Override
	public void partClosed(IWorkbenchPartReference partRef) {
		updateAndSendIdeContext();
	}

	@Override
	public void partDeactivated(IWorkbenchPartReference partRef) {
		/* No-op */ }

	@Override
	public void partOpened(IWorkbenchPartReference partRef) {
		updateAndSendIdeContext();
	}

	@Override
	public void partHidden(IWorkbenchPartReference partRef) {
		/* No-op */ }

	@Override
	public void partVisible(IWorkbenchPartReference partRef) {
		updateAndSendIdeContext();
	}

	@Override
	public void partInputChanged(IWorkbenchPartReference partRef) {
		updateAndSendIdeContext();
	}

	// --- Diff View Implementation ---

	private void showDiffView(String filePath, String newContent) {
		try {
			String originalContent = Files.readString(Paths.get(filePath));
			CompareConfiguration config = new CompareConfiguration();
			config.setLeftEditable(false);
			config.setRightEditable(true);
			config.setLeftLabel("Original: " + filePath);
			config.setRightLabel("Proposed Changes");
			NotifyingStringCompareInput input = new NotifyingStringCompareInput(config, filePath, originalContent,
					newContent);
			CompareUI.openCompareDialog(input);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private class NotifyingStringCompareInput extends org.eclipse.compare.CompareEditorInput {
		private final String filePath;
		private final ITypedElement left;
		private final StringTypedElement right;

		public NotifyingStringCompareInput(CompareConfiguration config, String filePath, String leftContent,
				String rightContent) {
			super(config);
			this.filePath = filePath;
			this.left = new StringTypedElement(filePath, leftContent);
			this.right = new StringTypedElement("new.txt", rightContent);
			setTitle("Compare Proposed Changes");
			setDirty(true);
		}

		@Override
		protected Object prepareInput(org.eclipse.core.runtime.IProgressMonitor monitor) {
			return new org.eclipse.compare.structuremergeviewer.DiffNode(left, right);
		}

		@Override
		public boolean okPressed() {
			String newContent = right.getContent();
			ObjectNode params = objectMapper.createObjectNode();
			params.put("filePath", filePath);
			params.put("content", newContent);
			sendMcpNotification("ide/diffAccepted", params);
			return super.okPressed();
		}

		@Override
		public void cancelPressed() {
			ObjectNode params = objectMapper.createObjectNode();
			params.put("filePath", filePath);
			sendMcpNotification("ide/diffRejected", params);
			super.cancelPressed();
		}
	}

	private static class StringTypedElement implements ITypedElement, IStreamContentAccessor, IEditableContent {
		private final String name;
		private String content;

		public StringTypedElement(String name, String content) {
			this.name = name;
			this.content = (content == null) ? "" : content;
		}

		public String getContent() {
			return content;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Image getImage() {
			return null;
		}

		@Override
		public String getType() {
			return "txt";
		}

		@Override
		public InputStream getContents() {
			return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public boolean isEditable() {
			return true;
		}

		@Override
		public void setContent(byte[] newContent) {
			this.content = new String(newContent, StandardCharsets.UTF_8);
		}

		@Override
		public ITypedElement replace(ITypedElement dest, ITypedElement src) {
			return null;
		}
	}

	// --- JSON & HTTP Communication Utilities ---

	public void sendMcpNotification(String method, Object params) {
		HttpExchange exchange = mcpExchange;
		if (exchange == null || !sseSessionActive.get()) {
			return;
		}
		try {
			ObjectNode notification = objectMapper.createObjectNode();
			notification.put("jsonrpc", "2.0");
			notification.put("method", method);
			notification.set("params", objectMapper.valueToTree(params));
			synchronized (exchange) {
				if (mcpExchange == exchange && sseSessionActive.get()) {
					sendSseMessage(exchange.getResponseBody(), notification);
				}
			}
		} catch (IOException e) {
			sseSessionActive.set(false);
			mcpExchange = null;
		}
	}

	private void sendJsonResponse(HttpExchange exchange, ObjectNode response) throws IOException {
		byte[] responseBytes = objectMapper.writeValueAsBytes(response);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		exchange.sendResponseHeaders(200, responseBytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(responseBytes);
		}
	}

	private void sendSseMessage(OutputStream os, Object message) throws IOException {
		String jsonPayload = objectMapper.writeValueAsString(message);
		String sseMessage = "data: " + jsonPayload + "\n\n";
		os.write(sseMessage.getBytes(StandardCharsets.UTF_8));
		os.flush();
	}

	private void sendJsonRpcError(HttpExchange exchange, JsonNode id, int code, String message) throws IOException {
		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", id);
		ObjectNode error = objectMapper.createObjectNode();
		error.put("code", code);
		error.put("message", message);
		response.set("error", error);
		byte[] responseBytes = objectMapper.writeValueAsBytes(response);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		exchange.sendResponseHeaders(400, responseBytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(responseBytes);
		}
	}

	private void sendToolSuccessResponse(HttpExchange exchange, JsonNode id, String message) throws IOException {
		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", id);
		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode content = objectMapper.createArrayNode();
		ObjectNode textContent = objectMapper.createObjectNode();
		textContent.put("type", "text");
		textContent.put("text", message);
		content.add(textContent);
		result.set("content", content);
		response.set("result", result);
		sendJsonResponse(exchange, response);
	}

	private void startKeepAliveThread(HttpExchange exchange, OutputStream os) {
		Thread keepAliveThread = new Thread(() -> {
			try {
				while (sseSessionActive.get() && mcpExchange == exchange) {
					Thread.sleep(15000);
					if (sseSessionActive.get() && mcpExchange == exchange) {
						synchronized (exchange) {
							try {
								os.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
								os.flush();
							} catch (IOException e) {
								break;
							}
						}
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				sseSessionActive.set(false);
				mcpExchange = null;
			}
		});
		keepAliveThread.setDaemon(true);
		keepAliveThread.setName("MCP-KeepAlive");
		keepAliveThread.start();
	}

	private void createDiscoveryFile(int port) throws IOException {
		IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
		IProject[] projects = workspaceRoot.getProjects();
		String pathSeparator = System.getProperty("path.separator");
		String workspacePath = Arrays.stream(projects).filter(IProject::isOpen).map(p -> p.getLocation().toOSString())
				.collect(Collectors.joining(pathSeparator));
		if (workspacePath.isEmpty()) {
			workspacePath = workspaceRoot.getLocation().toOSString();
		}
		DiscoveryFileContent discoveryContent = new DiscoveryFileContent();
		discoveryContent.port = port;
		discoveryContent.workspacePath = workspacePath;
		discoveryContent.authToken = this.authToken;
		discoveryContent.ideInfo = new IdeInfo();
		long pid = ProcessHandle.current().pid();
		String tempDir = System.getProperty("java.io.tmpdir");
		Path geminiDir = Paths.get(tempDir, "gemini", "ide");
		Files.createDirectories(geminiDir);
		String fileName = String.format("gemini-ide-server-%d-%d.json", pid, port);
		this.discoveryFilePath = geminiDir.resolve(fileName);
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(discoveryFilePath.toFile(), discoveryContent);
		discoveryFilePath.toFile().deleteOnExit();
	}

	private void deleteDiscoveryFile() {
		if (discoveryFilePath != null && Files.exists(discoveryFilePath)) {
			try {
				Files.delete(discoveryFilePath);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	// --- Data Classes for JSON Serialization ---

	public static class ToolResponse {
		public String status;
		public String message;

		public ToolResponse(String status, String message) {
			this.status = status;
			this.message = message;
		}
	}

	public static class DiffRequest {
		public String filePath;
		public String newContent;
	}

	public static class IdeContext {
		public String workspacePath;
		public List<OpenFile> openFiles;
		public String activeFile;
		public String selectedText;
		public CursorPosition cursorPosition;
	}

	public static class OpenFile {
		public String filePath;
		public long timestamp;
		public boolean active;
	}

	public static class CursorPosition {
		public int line;
		public int character;
	}

	public static class DiscoveryFileContent {
		public int port;
		public String workspacePath;
		public String authToken;
		public IdeInfo ideInfo;
	}

	public static class IdeInfo {
		public String name = "eclipse";
		public String displayName = "Eclipse IDE";
	}
}
