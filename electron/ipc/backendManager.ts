import { app, BrowserWindow } from 'electron'
import type { BackendProcessManager, BackendRuntime, BackendStatus } from './backendTypes'
import { JavaBackendManager } from './javaBackendManager'

/**
 * Runtime-neutral desktop backend facade.
 *
 * Packaged builds always use Java. Python is retained only as a source backup
 * and can be started in development with OPENGIS_BACKEND=python after an
 * environment has been prepared explicitly by a maintainer.
 */
export class BackendManager implements BackendProcessManager {
  private delegate: BackendProcessManager | null = null
  private mainWindow: BrowserWindow | null = null
  readonly runtime: BackendRuntime

  constructor() {
    const requested = process.env.OPENGIS_BACKEND?.trim().toLowerCase()
    if (app.isPackaged && requested === 'python') {
      console.warn('[Backend] OPENGIS_BACKEND=python ignored in packaged builds; Java is mandatory')
    }
    this.runtime = !app.isPackaged && requested === 'python' ? 'python' : 'java'
  }

  setMainWindow(window: BrowserWindow | null): void {
    this.mainWindow = window
    this.delegate?.setMainWindow(window)
  }

  getStatus(): BackendStatus {
    return this.delegate?.getStatus() ?? { status: 'stopped', runtime: this.runtime }
  }

  getPort(): number | null {
    return this.delegate?.getPort() ?? null
  }

  getWsToken(): string | null {
    return this.delegate?.getWsToken() ?? null
  }

  async start(): Promise<void> {
    const manager = await this.getDelegate()
    await manager.start()
  }

  async stop(): Promise<void> {
    await this.delegate?.stop()
  }

  async restart(): Promise<void> {
    const manager = await this.getDelegate()
    await manager.restart()
  }

  private async getDelegate(): Promise<BackendProcessManager> {
    if (!this.delegate) {
      if (this.runtime === 'python') {
        const { PythonManager } = await import('./pythonManager')
        const legacy = new PythonManager()
        this.delegate = {
          setMainWindow: (window) => legacy.setMainWindow(window),
          getStatus: () => {
            const status = legacy.getStatus()
            return {
              ...status,
              runtime: 'python',
              executablePath: status.pythonPath,
              diagnostics: ['Development-only Python backup runtime; never selected in packaged builds.'],
            }
          },
          getPort: () => legacy.getPort(),
          getWsToken: () => legacy.getWsToken(),
          start: () => legacy.start(),
          stop: () => legacy.stop(),
          restart: () => legacy.restart(),
        }
      } else {
        this.delegate = new JavaBackendManager()
      }
      this.delegate.setMainWindow(this.mainWindow)
    }
    return this.delegate
  }
}
