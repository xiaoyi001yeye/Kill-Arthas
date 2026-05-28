import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';
import AppLayout from './ui/AppLayout';
import AccessPage from './pages/AccessPage';
import ConsolePage from './pages/ConsolePage';
import CommandsPage from './pages/CommandsPage';
import './styles.css';

const queryClient = new QueryClient();

const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/access" replace /> },
      { path: 'access', element: <AccessPage /> },
      { path: 'console', element: <ConsolePage /> },
      { path: 'console/:targetId', element: <ConsolePage /> },
      { path: 'commands', element: <CommandsPage /> },
      { path: 'commands/:executionId', element: <CommandsPage /> }
    ]
  }
]);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#155eef', borderRadius: 6 } }}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ConfigProvider>
  </React.StrictMode>
);
