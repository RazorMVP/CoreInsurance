import { createBrowserRouter } from 'react-router-dom';

export const router = createBrowserRouter([
  { path: '/', element: <div className="p-6 text-foreground">authenticated</div> },
]);
