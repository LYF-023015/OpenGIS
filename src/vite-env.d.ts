/** 文件职责：共享基础能力：实现该文件名所对应的单一职责。 */
/// <reference types="vite/client" />
/// <reference types="vitest/globals" />

declare module "*?worker" {
  const WorkerFactory: {
    new (): Worker;
  };
  export default WorkerFactory;
}
