import { Avatar } from 'antd';
import { Box, FileText, TerminalSquare, User } from 'lucide-react';
import { NavLink, Outlet } from 'react-router-dom';

export default function AppLayout() {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">Fordring</div>
        <nav className="nav">
          <NavLink to="/access">
            <Box size={22} />
            <span>接入管理</span>
          </NavLink>
          <NavLink to="/console">
            <TerminalSquare size={22} />
            <span>控制台</span>
          </NavLink>
          <NavLink to="/commands">
            <FileText size={22} />
            <span>命令历史</span>
          </NavLink>
        </nav>
        <div className="user-footer">
          <Avatar icon={<User size={16} />} />
          <span>{import.meta.env.VITE_FORDRING_OPERATOR_NAME ?? 'admin'}</span>
        </div>
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
