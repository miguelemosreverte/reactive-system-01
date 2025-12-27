import React from 'react';
import { Switch, Tooltip } from 'antd';
import { SunOutlined, MoonOutlined } from '@ant-design/icons';
import { useTheme } from '../../context/ThemeContext';

const ThemeToggle: React.FC = () => {
  const { isDarkMode, toggleTheme } = useTheme();

  return (
    <Tooltip title={isDarkMode ? 'Switch to light mode' : 'Switch to dark mode'}>
      <Switch
        checked={isDarkMode}
        onChange={toggleTheme}
        checkedChildren={<MoonOutlined />}
        unCheckedChildren={<SunOutlined />}
      />
    </Tooltip>
  );
};

export default ThemeToggle;
