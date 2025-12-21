import { useState, useEffect } from 'react';
import { Card, Menu, Typography, Spin, Alert, theme } from 'antd';
import { FileTextOutlined } from '@ant-design/icons';
import type { MenuProps } from 'antd';

const { Title, Text, Paragraph } = Typography;

interface DocSection {
  id: string;
  title: string;
  file: string;
}

const DOC_SECTIONS: DocSection[] = [
  { id: 'overview', title: 'System Overview', file: 'DOCUMENTATION.md' },
  { id: 'ui', title: 'UI Component', file: 'ui/DOCUMENTATION.md' },
  { id: 'gateway', title: 'Gateway', file: 'gateway/DOCUMENTATION.md' },
  { id: 'flink', title: 'Flink', file: 'flink/DOCUMENTATION.md' },
  { id: 'drools', title: 'Drools', file: 'drools/DOCUMENTATION.md' },
];

function Documentation() {
  const { token } = theme.useToken();
  const [activeSection, setActiveSection] = useState('overview');
  const [content, setContent] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadDocumentation(activeSection);
  }, [activeSection]);

  const loadDocumentation = async (sectionId: string) => {
    setLoading(true);
    setError(null);
    const section = DOC_SECTIONS.find((s) => s.id === sectionId);
    if (!section) return;

    try {
      const response = await fetch(`/docs/${section.file}`);
      if (response.ok) {
        const text = await response.text();
        setContent(text);
      } else {
        setError(`Could not load ${section.file}`);
      }
    } catch (err) {
      setError(`Failed to fetch ${section.file}`);
    } finally {
      setLoading(false);
    }
  };

  const renderMarkdown = (md: string) => {
    const lines = md.split('\n');
    const elements: JSX.Element[] = [];
    let inCodeBlock = false;
    let codeContent = '';
    let inTable = false;
    let tableRows: string[][] = [];
    let listItems: string[] = [];
    let listType: 'ul' | 'ol' | null = null;

    const flushList = () => {
      if (listItems.length > 0) {
        elements.push(
          <ul key={elements.length} style={{ marginBottom: 16, paddingLeft: 24 }}>
            {listItems.map((item, i) => (
              <li key={i} style={{ marginBottom: 4 }}>
                <Text>{item}</Text>
              </li>
            ))}
          </ul>
        );
        listItems = [];
        listType = null;
      }
    };

    const flushTable = () => {
      if (tableRows.length > 0) {
        elements.push(
          <div key={elements.length} style={{ overflow: 'auto', marginBottom: 16 }}>
            <table
              style={{
                width: '100%',
                borderCollapse: 'collapse',
                fontSize: 14,
              }}
            >
              <thead>
                <tr>
                  {tableRows[0]?.map((cell, i) => (
                    <th
                      key={i}
                      style={{
                        padding: 8,
                        textAlign: 'left',
                        borderBottom: `2px solid ${token.colorBorderSecondary}`,
                        fontWeight: 600,
                      }}
                    >
                      {cell}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {tableRows.slice(2).map((row, i) => (
                  <tr key={i}>
                    {row.map((cell, j) => (
                      <td
                        key={j}
                        style={{
                          padding: 8,
                          borderBottom: `1px solid ${token.colorBorderSecondary}`,
                        }}
                      >
                        {cell}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        );
        tableRows = [];
        inTable = false;
      }
    };

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      if (line.startsWith('```')) {
        if (inCodeBlock) {
          elements.push(
            <pre
              key={elements.length}
              style={{
                background: token.colorBgLayout,
                padding: 16,
                borderRadius: token.borderRadius,
                overflow: 'auto',
                fontSize: 13,
                marginBottom: 16,
              }}
            >
              <code>{codeContent}</code>
            </pre>
          );
          codeContent = '';
          inCodeBlock = false;
        } else {
          flushList();
          flushTable();
          inCodeBlock = true;
        }
        continue;
      }

      if (inCodeBlock) {
        codeContent += line + '\n';
        continue;
      }

      if (line.includes('|') && line.trim().startsWith('|')) {
        flushList();
        const cells = line
          .split('|')
          .filter((c) => c.trim())
          .map((c) => c.trim());
        if (cells.length > 0) {
          inTable = true;
          tableRows.push(cells);
        }
        continue;
      } else if (inTable) {
        flushTable();
      }

      if (line.trim() === '') {
        flushList();
        continue;
      }

      if (line.startsWith('# ')) {
        flushList();
        elements.push(
          <Title key={elements.length} level={2} style={{ marginTop: 0 }}>
            {line.slice(2)}
          </Title>
        );
        continue;
      }
      if (line.startsWith('## ')) {
        flushList();
        elements.push(
          <Title key={elements.length} level={3} style={{ marginTop: 24 }}>
            {line.slice(3)}
          </Title>
        );
        continue;
      }
      if (line.startsWith('### ')) {
        flushList();
        elements.push(
          <Title key={elements.length} level={4} style={{ marginTop: 16 }}>
            {line.slice(4)}
          </Title>
        );
        continue;
      }
      if (line.startsWith('#### ')) {
        flushList();
        elements.push(
          <Title key={elements.length} level={5} style={{ marginTop: 12 }}>
            {line.slice(5)}
          </Title>
        );
        continue;
      }

      if (line.match(/^[-*] /)) {
        if (listType !== 'ul') {
          flushList();
          listType = 'ul';
        }
        listItems.push(line.slice(2));
        continue;
      }
      if (line.match(/^\d+\. /)) {
        if (listType !== 'ol') {
          flushList();
          listType = 'ol';
        }
        listItems.push(line.replace(/^\d+\. /, ''));
        continue;
      }

      flushList();
      const formatted = line
        .replace(/`([^`]+)`/g, '<code style="background: ' + token.colorBgLayout + '; padding: 2px 6px; border-radius: 4px; font-size: 13px;">$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');

      elements.push(
        <Paragraph key={elements.length} style={{ marginBottom: 12 }}>
          <span dangerouslySetInnerHTML={{ __html: formatted }} />
        </Paragraph>
      );
    }

    flushList();
    flushTable();

    return elements;
  };

  const menuItems: MenuProps['items'] = DOC_SECTIONS.map((section) => ({
    key: section.id,
    icon: <FileTextOutlined />,
    label: section.title,
  }));

  return (
    <div style={{ display: 'flex', gap: 24, minHeight: 600 }}>
      <Card size="small" style={{ width: 200, flexShrink: 0 }}>
        <Title level={5} style={{ marginTop: 0, marginBottom: 16 }}>
          Documentation
        </Title>
        <Menu
          mode="inline"
          selectedKeys={[activeSection]}
          onClick={(e) => setActiveSection(e.key)}
          items={menuItems}
          style={{ border: 'none' }}
        />
      </Card>

      <Card style={{ flex: 1 }}>
        {loading ? (
          <div style={{ textAlign: 'center', padding: 48 }}>
            <Spin size="large" />
            <Text type="secondary" style={{ display: 'block', marginTop: 16 }}>
              Loading documentation...
            </Text>
          </div>
        ) : error ? (
          <Alert message="Error" description={error} type="error" showIcon />
        ) : (
          <div>{renderMarkdown(content)}</div>
        )}
      </Card>
    </div>
  );
}

export default Documentation;
