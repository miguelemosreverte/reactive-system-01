import { useState, useEffect } from 'react'

interface DocSection {
  id: string
  title: string
  file: string
}

const DOC_SECTIONS: DocSection[] = [
  { id: 'overview', title: 'System Overview', file: 'DOCUMENTATION.md' },
  { id: 'ui', title: 'UI Component', file: 'ui/DOCUMENTATION.md' },
  { id: 'gateway', title: 'Gateway', file: 'gateway/DOCUMENTATION.md' },
  { id: 'flink', title: 'Flink', file: 'flink/DOCUMENTATION.md' },
  { id: 'drools', title: 'Drools', file: 'drools/DOCUMENTATION.md' },
]

function Documentation() {
  const [activeSection, setActiveSection] = useState('overview')
  const [content, setContent] = useState<string>('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    loadDocumentation(activeSection)
  }, [activeSection])

  const loadDocumentation = async (sectionId: string) => {
    setLoading(true)
    const section = DOC_SECTIONS.find(s => s.id === sectionId)
    if (!section) return

    try {
      const response = await fetch(`/docs/${section.file}`)
      if (response.ok) {
        const text = await response.text()
        setContent(text)
      } else {
        setContent(`# Documentation Not Found\n\nCould not load ${section.file}`)
      }
    } catch (error) {
      setContent(`# Error Loading Documentation\n\nFailed to fetch ${section.file}`)
    } finally {
      setLoading(false)
    }
  }

  // Simple markdown renderer
  const renderMarkdown = (md: string) => {
    const lines = md.split('\n')
    const elements: JSX.Element[] = []
    let inCodeBlock = false
    let codeContent = ''
    let inTable = false
    let tableRows: string[][] = []
    let listItems: string[] = []
    let listType: 'ul' | 'ol' | null = null

    const flushList = () => {
      if (listItems.length > 0) {
        const ListTag = listType === 'ol' ? 'ol' : 'ul'
        elements.push(
          <ListTag key={elements.length} style={styles.list}>
            {listItems.map((item, i) => (
              <li key={i} style={styles.listItem}>{item}</li>
            ))}
          </ListTag>
        )
        listItems = []
        listType = null
      }
    }

    const flushTable = () => {
      if (tableRows.length > 0) {
        elements.push(
          <div key={elements.length} style={styles.tableWrapper}>
            <table style={styles.table}>
              <thead>
                <tr>
                  {tableRows[0]?.map((cell, i) => (
                    <th key={i} style={styles.th}>{cell}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {tableRows.slice(2).map((row, i) => (
                  <tr key={i}>
                    {row.map((cell, j) => (
                      <td key={j} style={styles.td}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
        tableRows = []
        inTable = false
      }
    }

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]

      // Code blocks
      if (line.startsWith('```')) {
        if (inCodeBlock) {
          elements.push(
            <pre key={elements.length} style={styles.codeBlock}>
              <code>{codeContent}</code>
            </pre>
          )
          codeContent = ''
          inCodeBlock = false
        } else {
          flushList()
          flushTable()
          inCodeBlock = true
        }
        continue
      }

      if (inCodeBlock) {
        codeContent += line + '\n'
        continue
      }

      // Tables
      if (line.includes('|') && line.trim().startsWith('|')) {
        flushList()
        const cells = line.split('|').filter(c => c.trim()).map(c => c.trim())
        if (cells.length > 0) {
          inTable = true
          tableRows.push(cells)
        }
        continue
      } else if (inTable) {
        flushTable()
      }

      // Empty lines
      if (line.trim() === '') {
        flushList()
        continue
      }

      // Headers
      if (line.startsWith('# ')) {
        flushList()
        elements.push(<h1 key={elements.length} style={styles.h1}>{line.slice(2)}</h1>)
        continue
      }
      if (line.startsWith('## ')) {
        flushList()
        elements.push(<h2 key={elements.length} style={styles.h2}>{line.slice(3)}</h2>)
        continue
      }
      if (line.startsWith('### ')) {
        flushList()
        elements.push(<h3 key={elements.length} style={styles.h3}>{line.slice(4)}</h3>)
        continue
      }
      if (line.startsWith('#### ')) {
        flushList()
        elements.push(<h4 key={elements.length} style={styles.h4}>{line.slice(5)}</h4>)
        continue
      }

      // Lists
      if (line.match(/^[-*] /)) {
        if (listType !== 'ul') {
          flushList()
          listType = 'ul'
        }
        listItems.push(line.slice(2))
        continue
      }
      if (line.match(/^\d+\. /)) {
        if (listType !== 'ol') {
          flushList()
          listType = 'ol'
        }
        listItems.push(line.replace(/^\d+\. /, ''))
        continue
      }

      // Inline code and bold
      flushList()
      let formatted = line
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')

      elements.push(
        <p
          key={elements.length}
          style={styles.paragraph}
          dangerouslySetInnerHTML={{ __html: formatted }}
        />
      )
    }

    flushList()
    flushTable()

    return elements
  }

  return (
    <div style={styles.container}>
      <div style={styles.sidebar}>
        <h3 style={styles.sidebarTitle}>Documentation</h3>
        {DOC_SECTIONS.map(section => (
          <button
            key={section.id}
            onClick={() => setActiveSection(section.id)}
            style={{
              ...styles.navButton,
              ...(activeSection === section.id ? styles.navButtonActive : {})
            }}
          >
            {section.title}
          </button>
        ))}
      </div>

      <div style={styles.content}>
        {loading ? (
          <div style={styles.loading}>Loading...</div>
        ) : (
          <div style={styles.markdown}>
            {renderMarkdown(content)}
          </div>
        )}
      </div>
    </div>
  )
}

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    display: 'flex',
    gap: '1.5rem',
    height: '600px',
    background: 'rgba(255, 255, 255, 0.05)',
    borderRadius: '1rem',
    padding: '1.5rem',
    backdropFilter: 'blur(10px)',
    border: '1px solid rgba(255, 255, 255, 0.1)',
  },
  sidebar: {
    width: '180px',
    flexShrink: 0,
    borderRight: '1px solid rgba(255, 255, 255, 0.1)',
    paddingRight: '1rem',
  },
  sidebarTitle: {
    margin: '0 0 1rem 0',
    fontSize: '0.8rem',
    fontWeight: 600,
    color: '#94a3b8',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
  },
  navButton: {
    display: 'block',
    width: '100%',
    padding: '0.5rem 0.75rem',
    marginBottom: '0.25rem',
    border: 'none',
    borderRadius: '0.5rem',
    background: 'transparent',
    color: '#94a3b8',
    fontSize: '0.85rem',
    textAlign: 'left',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
  },
  navButtonActive: {
    background: 'rgba(79, 70, 229, 0.2)',
    color: '#a5b4fc',
  },
  content: {
    flex: 1,
    overflow: 'auto',
  },
  loading: {
    color: '#64748b',
    textAlign: 'center',
    padding: '2rem',
  },
  markdown: {
    color: '#e2e8f0',
    lineHeight: 1.6,
  },
  h1: {
    fontSize: '1.5rem',
    fontWeight: 700,
    color: '#fff',
    marginBottom: '1rem',
    paddingBottom: '0.5rem',
    borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
  },
  h2: {
    fontSize: '1.25rem',
    fontWeight: 600,
    color: '#fff',
    marginTop: '1.5rem',
    marginBottom: '0.75rem',
  },
  h3: {
    fontSize: '1rem',
    fontWeight: 600,
    color: '#e2e8f0',
    marginTop: '1.25rem',
    marginBottom: '0.5rem',
  },
  h4: {
    fontSize: '0.9rem',
    fontWeight: 600,
    color: '#cbd5e1',
    marginTop: '1rem',
    marginBottom: '0.5rem',
  },
  paragraph: {
    marginBottom: '0.75rem',
    fontSize: '0.9rem',
    color: '#cbd5e1',
  },
  codeBlock: {
    background: 'rgba(0, 0, 0, 0.4)',
    padding: '1rem',
    borderRadius: '0.5rem',
    overflow: 'auto',
    fontSize: '0.8rem',
    fontFamily: 'monospace',
    color: '#a5b4fc',
    marginBottom: '1rem',
  },
  list: {
    margin: '0 0 1rem 1.5rem',
    padding: 0,
    fontSize: '0.9rem',
  },
  listItem: {
    marginBottom: '0.35rem',
    color: '#cbd5e1',
  },
  tableWrapper: {
    overflow: 'auto',
    marginBottom: '1rem',
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '0.85rem',
  },
  th: {
    padding: '0.5rem',
    textAlign: 'left',
    borderBottom: '1px solid rgba(255, 255, 255, 0.2)',
    color: '#fff',
    fontWeight: 600,
  },
  td: {
    padding: '0.5rem',
    borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
    color: '#cbd5e1',
  },
}

export default Documentation
