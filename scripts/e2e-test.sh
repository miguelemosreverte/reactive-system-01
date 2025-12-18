#!/bin/bash
# End-to-End Test Script

source "$(dirname "${BASH_SOURCE[0]}")/utils.sh"

run_e2e_test() {
    local passed=0
    local failed=0

    print_info "Running deterministic E2E test..."
    echo ""

    # Test 1: Send counter event with value=50 (should trigger WARNING)
    print_info "Test 1: Counter value 50 should trigger WARNING alert"
    local response1
    response1=$(curl -s -X POST "http://localhost:8080/api/counter" \
        -H "Content-Type: application/json" \
        -d '{"action": "set", "value": 50}' 2>/dev/null)

    # Wait for processing
    sleep 2

    # Check the result via API
    local result1
    result1=$(curl -s "http://localhost:8080/api/counter/status" 2>/dev/null)

    if echo "$result1" | grep -q '"alert":"WARNING"'; then
        print_success "Test 1 PASSED: Counter=50 triggered WARNING"
        ((passed++))
    else
        print_error "Test 1 FAILED: Expected WARNING alert"
        echo "  Response: $result1"
        ((failed++))
    fi

    # Test 2: Send counter event with value=150 (should trigger CRITICAL)
    print_info "Test 2: Counter value 150 should trigger CRITICAL alert"
    local response2
    response2=$(curl -s -X POST "http://localhost:8080/api/counter" \
        -H "Content-Type: application/json" \
        -d '{"action": "set", "value": 150}' 2>/dev/null)

    # Wait for processing
    sleep 2

    local result2
    result2=$(curl -s "http://localhost:8080/api/counter/status" 2>/dev/null)

    if echo "$result2" | grep -q '"alert":"CRITICAL"'; then
        print_success "Test 2 PASSED: Counter=150 triggered CRITICAL"
        ((passed++))
    else
        print_error "Test 2 FAILED: Expected CRITICAL alert"
        echo "  Response: $result2"
        ((failed++))
    fi

    # Test 3: Reset counter to 0 (should trigger RESET)
    print_info "Test 3: Counter value 0 should trigger RESET notification"
    local response3
    response3=$(curl -s -X POST "http://localhost:8080/api/counter" \
        -H "Content-Type: application/json" \
        -d '{"action": "set", "value": 0}' 2>/dev/null)

    # Wait for processing
    sleep 2

    local result3
    result3=$(curl -s "http://localhost:8080/api/counter/status" 2>/dev/null)

    if echo "$result3" | grep -q '"alert":"RESET"'; then
        print_success "Test 3 PASSED: Counter=0 triggered RESET"
        ((passed++))
    else
        print_error "Test 3 FAILED: Expected RESET notification"
        echo "  Response: $result3"
        ((failed++))
    fi

    # Test 4: WebSocket connectivity
    print_info "Test 4: WebSocket connectivity check"
    if command -v wscat &>/dev/null; then
        if timeout 5 wscat -c "ws://localhost:8080/ws" -x '{"type":"ping"}' &>/dev/null; then
            print_success "Test 4 PASSED: WebSocket connection successful"
            ((passed++))
        else
            print_error "Test 4 FAILED: WebSocket connection failed"
            ((failed++))
        fi
    else
        print_warning "Test 4 SKIPPED: wscat not installed"
    fi

    echo ""
    print_header "E2E Test Results"
    echo ""
    print_info "Passed: $passed"
    if [[ $failed -gt 0 ]]; then
        print_error "Failed: $failed"
        return 1
    else
        print_success "All tests passed!"
        return 0
    fi
}

# Allow running directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    run_e2e_test
fi
