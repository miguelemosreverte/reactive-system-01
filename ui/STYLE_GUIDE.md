# UI Style Guide

This document defines the visual design system for all UIs in the Reactive System project. The design is based on **Ant Design**, matching Apache Flink's dashboard style.

## Design System

We use [Ant Design](https://ant.design/) for React, the same design system that Apache Flink uses (via NG-ZORRO for Angular).

## Color Palette

### Primary Colors

| Token | Light Mode | Dark Mode | Usage |
|-------|------------|-----------|-------|
| `colorPrimary` | `#1890ff` | `#1890ff` | Primary actions, links, active states |
| `colorSuccess` | `#52c41a` | `#52c41a` | Success states, healthy status |
| `colorWarning` | `#faad14` | `#faad14` | Warning states, caution |
| `colorError` | `#ff4d4f` | `#ff4d4f` | Error states, failures |
| `colorInfo` | `#1890ff` | `#1890ff` | Informational elements |

### Background Colors

| Token | Light Mode | Dark Mode | Usage |
|-------|------------|-----------|-------|
| `colorBgContainer` | `#ffffff` | `#141414` | Cards, containers |
| `colorBgLayout` | `#f5f5f5` | `#000000` | Page background |
| `colorBgElevated` | `#ffffff` | `#1f1f1f` | Modals, dropdowns |

### Text Colors

| Token | Light Mode | Dark Mode | Usage |
|-------|------------|-----------|-------|
| `colorText` | `rgba(0,0,0,0.88)` | `rgba(255,255,255,0.85)` | Primary text |
| `colorTextSecondary` | `rgba(0,0,0,0.65)` | `rgba(255,255,255,0.65)` | Secondary text |
| `colorTextTertiary` | `rgba(0,0,0,0.45)` | `rgba(255,255,255,0.45)` | Disabled, hints |

### Service Colors (Project-Specific)

These colors identify different services in the architecture:

| Service | Color | Hex |
|---------|-------|-----|
| Gateway | Green | `#52c41a` |
| Kafka | Orange | `#faad14` |
| Flink | Purple | `#722ed1` |
| Drools | Red | `#ff4d4f` |

## Typography

### Font Family

```css
font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
             'Helvetica Neue', Arial, 'Noto Sans', sans-serif,
             'Apple Color Emoji', 'Segoe UI Emoji', 'Segoe UI Symbol',
             'Noto Color Emoji';
```

### Font Sizes

| Token | Size | Usage |
|-------|------|-------|
| `fontSizeHeading1` | 38px | Page titles |
| `fontSizeHeading2` | 30px | Section headers |
| `fontSizeHeading3` | 24px | Card headers |
| `fontSizeHeading4` | 20px | Sub-sections |
| `fontSizeHeading5` | 16px | Component headers |
| `fontSize` | 14px | Body text (default) |
| `fontSizeSM` | 12px | Small text, labels |

### Font Weights

| Weight | Value | Usage |
|--------|-------|-------|
| Regular | 400 | Body text |
| Medium | 500 | Headings, emphasis |
| Bold | 600 | Strong emphasis (rare) |

### Line Height

- Default: `1.5715`
- Headings: `1.2`

## Spacing

Ant Design uses an 8px base spacing unit:

| Token | Value | Usage |
|-------|-------|-------|
| `marginXS` | 8px | Tight spacing |
| `marginSM` | 12px | Small spacing |
| `margin` | 16px | Default spacing |
| `marginMD` | 20px | Medium spacing |
| `marginLG` | 24px | Large spacing |
| `marginXL` | 32px | Extra large spacing |

## Border Radius

| Token | Value | Usage |
|-------|-------|-------|
| `borderRadius` | 6px | Default (buttons, inputs) |
| `borderRadiusSM` | 4px | Small elements |
| `borderRadiusLG` | 8px | Cards, modals |

## Shadows

| Token | Usage |
|-------|-------|
| `boxShadow` | Default elevation |
| `boxShadowSecondary` | Dropdowns, popovers |

## Component Guidelines

### Layout

Use Ant Design's Layout components:

```tsx
import { Layout } from 'antd';
const { Header, Sider, Content } = Layout;
```

- **Sider**: 240px width for navigation
- **Header**: 64px height with theme toggle
- **Content**: Flexible main content area

### Navigation

Use Ant Design's Menu component:

```tsx
import { Menu } from 'antd';
```

- Use icons from `@ant-design/icons`
- Group related items with `Menu.ItemGroup`
- External links should show an external icon

### Cards

Use for content grouping:

```tsx
import { Card } from 'antd';

<Card title="Section Title" size="small">
  Content here
</Card>
```

### Tables

Use for data display:

```tsx
import { Table } from 'antd';
```

- Always include column headers
- Use pagination for large datasets
- Include sorting where applicable

### Status Indicators

Use Ant Design's Badge and Tag:

```tsx
import { Badge, Tag } from 'antd';

// Connection status
<Badge status="success" text="Connected" />
<Badge status="error" text="Disconnected" />

// Service tags
<Tag color="green">Gateway</Tag>
<Tag color="orange">Kafka</Tag>
<Tag color="purple">Flink</Tag>
<Tag color="red">Drools</Tag>
```

### Buttons

```tsx
import { Button } from 'antd';

<Button type="primary">Primary Action</Button>
<Button>Secondary Action</Button>
<Button type="text">Text Button</Button>
```

### Charts

Use `@ant-design/charts` for consistency:

```tsx
import { Line, Column } from '@ant-design/charts';
```

## Theme Configuration

### Light Theme (Default)

```tsx
import { ConfigProvider, theme } from 'antd';

<ConfigProvider
  theme={{
    algorithm: theme.defaultAlgorithm,
    token: {
      colorPrimary: '#1890ff',
      borderRadius: 6,
    },
  }}
>
  <App />
</ConfigProvider>
```

### Dark Theme

```tsx
<ConfigProvider
  theme={{
    algorithm: theme.darkAlgorithm,
    token: {
      colorPrimary: '#1890ff',
      borderRadius: 6,
    },
  }}
>
  <App />
</ConfigProvider>
```

### Theme Toggle

Place a sun/moon toggle button in the header (top-right):

```tsx
import { Switch } from 'antd';
import { SunOutlined, MoonOutlined } from '@ant-design/icons';

<Switch
  checkedChildren={<MoonOutlined />}
  unCheckedChildren={<SunOutlined />}
  checked={isDarkMode}
  onChange={setIsDarkMode}
/>
```

## File Structure

```
ui/
├── src/
│   ├── components/          # Shared components
│   │   ├── layout/          # Layout components
│   │   │   ├── AppLayout.tsx
│   │   │   ├── AppHeader.tsx
│   │   │   └── AppSider.tsx
│   │   ├── common/          # Common UI components
│   │   │   ├── ServiceTag.tsx
│   │   │   ├── StatusBadge.tsx
│   │   │   └── ThemeToggle.tsx
│   │   └── charts/          # Chart components
│   │       ├── ThroughputChart.tsx
│   │       └── ResourceChart.tsx
│   ├── pages/               # Page components
│   │   ├── counter/         # Counter application pages
│   │   ├── benchmark/       # Benchmark dashboard pages
│   │   └── status/          # System status pages
│   ├── hooks/               # Custom React hooks
│   ├── context/             # React context (theme, etc.)
│   ├── utils/               # Utility functions
│   ├── types/               # TypeScript types
│   └── theme/               # Theme configuration
│       └── tokens.ts        # Design tokens
├── STYLE_GUIDE.md           # This document
└── package.json
```

## Accessibility

- Maintain WCAG 2.1 AA compliance
- Use semantic HTML elements
- Ensure sufficient color contrast (4.5:1 minimum)
- Support keyboard navigation
- Include proper ARIA labels

## Responsive Design

Use Ant Design's Grid system:

```tsx
import { Row, Col } from 'antd';

<Row gutter={[16, 16]}>
  <Col xs={24} sm={12} lg={8}>
    <Card>Content</Card>
  </Col>
</Row>
```

Breakpoints:
- `xs`: < 576px (mobile)
- `sm`: >= 576px (tablet)
- `md`: >= 768px (small desktop)
- `lg`: >= 992px (desktop)
- `xl`: >= 1200px (large desktop)
- `xxl`: >= 1600px (extra large)
