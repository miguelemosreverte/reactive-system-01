import { useEffect, useRef, useState, useCallback } from 'react'

interface WebSocketMessage {
  type: string
  [key: string]: any
}

interface UseWebSocketOptions {
  onMessage?: (message: WebSocketMessage) => void
  onConnect?: () => void
  onDisconnect?: () => void
}

export function useWebSocket(options: UseWebSocketOptions = {}) {
  const [isConnected, setIsConnected] = useState(false)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [isReconnecting, setIsReconnecting] = useState(false)
  const [reconnectAttempt, setReconnectAttempt] = useState(0)
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectTimeoutRef = useRef<number | null>(null)

  // Store callbacks in refs to avoid recreating the connect function
  const onMessageRef = useRef(options.onMessage)
  const onConnectRef = useRef(options.onConnect)
  const onDisconnectRef = useRef(options.onDisconnect)

  // Update refs when options change
  useEffect(() => {
    onMessageRef.current = options.onMessage
    onConnectRef.current = options.onConnect
    onDisconnectRef.current = options.onDisconnect
  }, [options.onMessage, options.onConnect, options.onDisconnect])

  const connect = useCallback(() => {
    // Determine WebSocket URL based on environment
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}/ws`

    console.log('Connecting to WebSocket:', wsUrl)

    const ws = new WebSocket(wsUrl)

    ws.onopen = () => {
      console.log('WebSocket connected')
      setIsConnected(true)
      setIsReconnecting(false)
      setReconnectAttempt(0)
      onConnectRef.current?.()
    }

    ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data)
        console.log('WebSocket message:', message)

        if (message.type === 'connected') {
          setSessionId(message.sessionId)
        }

        onMessageRef.current?.(message)
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error)
      }
    }

    ws.onclose = () => {
      console.log('WebSocket disconnected')
      setIsConnected(false)
      setIsReconnecting(true)
      onDisconnectRef.current?.()

      // Attempt to reconnect after 3 seconds
      reconnectTimeoutRef.current = window.setTimeout(() => {
        setReconnectAttempt(prev => prev + 1)
        console.log('Attempting to reconnect...')
        connect()
      }, 3000)
    }

    ws.onerror = (error) => {
      console.error('WebSocket error:', error)
    }

    wsRef.current = ws
  }, [])

  useEffect(() => {
    connect()

    return () => {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current)
      }
      if (wsRef.current) {
        wsRef.current.close()
      }
    }
  }, [connect])

  const sendMessage = useCallback((message: WebSocketMessage) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(message))
    } else {
      console.warn('WebSocket not connected, message not sent:', message)
    }
  }, [])

  return {
    isConnected,
    sessionId,
    sendMessage,
    isReconnecting,
    reconnectAttempt
  }
}
