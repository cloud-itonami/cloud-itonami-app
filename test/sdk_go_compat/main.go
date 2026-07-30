package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type bearerTransport struct {
	token string
	base  http.RoundTripper
}

func (t bearerTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	cloned := request.Clone(request.Context())
	cloned.Header = request.Header.Clone()
	cloned.Header.Set("Authorization", "Bearer "+t.token)
	return t.base.RoundTrip(cloned)
}

func main() {
	baseURL := os.Getenv("SDK_FIXTURE_URL")
	if baseURL == "" {
		baseURL = "http://127.0.0.1:18473"
	}
	token := os.Getenv("SDK_MCP_TOKEN")
	if token == "" {
		token = "fixture-mcp-token"
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	httpClient := &http.Client{
		Transport: bearerTransport{token: token, base: http.DefaultTransport},
		Timeout:   15 * time.Second,
	}
	transport := &mcp.StreamableClientTransport{
		Endpoint:             baseURL + "/mcp",
		HTTPClient:           httpClient,
		DisableStandaloneSSE: true,
	}
	client := mcp.NewClient(
		&mcp.Implementation{Name: "cloud-itonami-sdk-test", Version: "1"},
		&mcp.ClientOptions{Capabilities: &mcp.ClientCapabilities{}},
	)
	session, err := client.Connect(ctx, transport, nil)
	if err != nil {
		panic(fmt.Errorf("connect official MCP Go SDK: %w", err))
	}
	defer session.Close()

	initialized := session.InitializeResult()
	if initialized == nil || initialized.ProtocolVersion != "2026-07-28" {
		panic(fmt.Errorf("unexpected negotiated protocol: %+v", initialized))
	}
	if initialized.ServerInfo == nil ||
		initialized.ServerInfo.Name != "cloud-itonami" {
		panic(fmt.Errorf("unexpected server info: %+v", initialized.ServerInfo))
	}

	tools, err := session.ListTools(ctx, nil)
	if err != nil {
		panic(fmt.Errorf("list tools: %w", err))
	}
	found := false
	for _, tool := range tools.Tools {
		if tool.Name == "workspace_snapshot" {
			found = true
			break
		}
	}
	if !found {
		panic("workspace_snapshot tool was not advertised")
	}

	fmt.Println("official MCP Go SDK 2026-07-28 compatibility: ok")
}
