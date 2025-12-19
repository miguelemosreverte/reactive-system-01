import React, { useMemo } from 'react'
import { getJaegerUrl, getGrafanaUrl, getFlinkUrl } from '../utils/urls'

export type NavItem = 'demo' | 'status' | 'traces' | 'docs'

interface SidebarProps {
  activeItem: NavItem
  onNavigate: (item: NavItem) => void
  isConnected: boolean
}

const Sidebar: React.FC<SidebarProps> = ({ activeItem, onNavigate, isConnected }) => {
  const navItems: { id: NavItem; label: string; icon: string }[] = [
    { id: 'demo', label: 'Live Demo', icon: '▶' },
    { id: 'status', label: 'System Status', icon: '◉' },
    { id: 'traces', label: 'Traces', icon: '⟡' },
    { id: 'docs', label: 'Documentation', icon: '◔' }
  ]

  const externalLinks = useMemo(() => [
    { label: 'Jaeger UI', url: getJaegerUrl(), icon: '⊕' },
    { label: 'Grafana', url: getGrafanaUrl(), icon: '◈' },
    { label: 'Flink Dashboard', url: getFlinkUrl(), icon: '◇' }
  ], [])

  return (
    <aside style={styles.sidebar}>
      <div style={styles.logo}>
        <span style={styles.logoIcon}>⟁</span>
        <span style={styles.logoText}>Reactive System</span>
      </div>

      <div style={styles.connectionBadge}>
        <span style={{
          ...styles.statusDot,
          background: isConnected ? '#10b981' : '#ef4444'
        }} />
        <span style={styles.connectionText}>
          {isConnected ? 'Connected' : 'Disconnected'}
        </span>
      </div>

      <nav style={styles.nav}>
        <div style={styles.navSection}>
          <span style={styles.navSectionTitle}>Application</span>
          {navItems.map(item => (
            <button
              key={item.id}
              onClick={() => onNavigate(item.id)}
              style={{
                ...styles.navItem,
                ...(activeItem === item.id ? styles.navItemActive : {})
              }}
            >
              <span style={styles.navIcon}>{item.icon}</span>
              <span>{item.label}</span>
            </button>
          ))}
        </div>

        <div style={styles.navSection}>
          <span style={styles.navSectionTitle}>Observability</span>
          {externalLinks.map(link => (
            <a
              key={link.url}
              href={link.url}
              target="_blank"
              rel="noopener noreferrer"
              style={styles.navItem}
            >
              <span style={styles.navIcon}>{link.icon}</span>
              <span>{link.label}</span>
              <span style={styles.externalIcon}>↗</span>
            </a>
          ))}
        </div>
      </nav>

      <div style={styles.footer}>
        <span style={styles.footerText}>Event-Driven Architecture</span>
        <span style={styles.footerSubtext}>Kafka • Flink • Drools</span>
      </div>
    </aside>
  )
}

const styles: { [key: string]: React.CSSProperties } = {
  sidebar: {
    width: '240px',
    minHeight: '100vh',
    background: 'rgba(0, 0, 0, 0.4)',
    borderRight: '1px solid rgba(255, 255, 255, 0.1)',
    display: 'flex',
    flexDirection: 'column',
    padding: '1rem 0'
  },
  logo: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.75rem',
    padding: '0 1rem 1rem',
    borderBottom: '1px solid rgba(255, 255, 255, 0.1)'
  },
  logoIcon: {
    fontSize: '1.5rem',
    color: '#818cf8'
  },
  logoText: {
    fontSize: '1rem',
    fontWeight: 600,
    color: '#fff'
  },
  connectionBadge: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    padding: '0.75rem 1rem',
    margin: '0.5rem 1rem',
    background: 'rgba(255, 255, 255, 0.05)',
    borderRadius: '0.5rem'
  },
  statusDot: {
    width: '8px',
    height: '8px',
    borderRadius: '50%'
  },
  connectionText: {
    fontSize: '0.75rem',
    color: '#94a3b8'
  },
  nav: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    gap: '1rem',
    padding: '0.5rem 0'
  },
  navSection: {
    display: 'flex',
    flexDirection: 'column',
    gap: '0.25rem'
  },
  navSectionTitle: {
    fontSize: '0.65rem',
    fontWeight: 600,
    color: '#64748b',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
    padding: '0.5rem 1rem'
  },
  navItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.75rem',
    padding: '0.6rem 1rem',
    color: '#94a3b8',
    fontSize: '0.85rem',
    textDecoration: 'none',
    border: 'none',
    background: 'transparent',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    textAlign: 'left',
    width: '100%'
  },
  navItemActive: {
    background: 'rgba(79, 70, 229, 0.2)',
    color: '#fff',
    borderRight: '2px solid #818cf8'
  },
  navIcon: {
    fontSize: '1rem',
    width: '20px',
    textAlign: 'center'
  },
  externalIcon: {
    marginLeft: 'auto',
    fontSize: '0.7rem',
    color: '#64748b'
  },
  footer: {
    padding: '1rem',
    borderTop: '1px solid rgba(255, 255, 255, 0.1)',
    textAlign: 'center'
  },
  footerText: {
    display: 'block',
    fontSize: '0.7rem',
    color: '#64748b'
  },
  footerSubtext: {
    display: 'block',
    fontSize: '0.65rem',
    color: '#475569',
    marginTop: '0.25rem'
  }
}

export default Sidebar
