/** 文件职责：共享基础能力：进程或应用启动入口。 */
import React from "react";
import ReactDOM from "react-dom/client";
import App from "@/app/App";
import "@/shared/styles/globals.css";
// Side-effect import: wires @monaco-editor/react to the locally bundled
// monaco-editor so the CDN loader.js never gets requested (which our
// Electron CSP would block anyway). MUST be imported before App.
import "@/shared/lib/monacoSetup";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
