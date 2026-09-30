// Command roundtable is the engine binary — `roundtable serve` runs the HTTP daemon;
// `roundtable users add` seeds the multi-user account store. The interactive setup wizard
// (Phase 3 of the roadmap) isn't built yet — this is deliberately just enough to run Phase
// 1's daemon end-to-end and let Phase 2 (desktop repoint) have something real to talk to.
package main

import (
	"flag"
	"fmt"
	"log"
	"os"

	"dialex/pkg/api"
	"dialex/pkg/service"
	"dialex/pkg/store"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	switch os.Args[1] {
	case "serve":
		runServe(os.Args[2:])
	case "users":
		runUsers(os.Args[2:])
	case "service":
		runService(os.Args[2:])
	case "-h", "--help", "help":
		printUsage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", os.Args[1])
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println(`roundtable — the Roundtable engine

Usage:
  roundtable serve [flags]        Run the HTTP/SSE daemon
  roundtable users add <name>     Add an account to the multi-user store (--dir to target
                                   a non-default store, e.g. for scripting/testing)
  roundtable service install      Register the engine to start at login (launchd on macOS,
                                   systemd --user on Linux; not yet supported on Windows)
  roundtable service uninstall    Remove that login-time registration
  roundtable service status       Report whether it's currently registered

serve flags:
  --host string             Address to bind (default "127.0.0.1:7890")
  --allow-insecure-lan      Required to bind to anything other than loopback
  --tls                     Serve HTTPS (self-signed cert, generated once, if none given)
  --tls-cert string         Existing certificate file (with --tls-key)
  --tls-key string          Existing key file (with --tls-cert)
  --dir string              Override the config directory (default: platform-standard)`)
}

func runServe(args []string) {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	host := fs.String("host", "127.0.0.1:7890", "address to bind")
	allowInsecureLAN := fs.Bool("allow-insecure-lan", false, "required to bind to a non-loopback address")
	useTLS := fs.Bool("tls", false, "serve HTTPS")
	tlsCert := fs.String("tls-cert", "", "existing certificate file")
	tlsKey := fs.String("tls-key", "", "existing key file")
	dir := fs.String("dir", "", "override the config directory")
	fs.Parse(args)

	configDir := *dir
	if configDir == "" {
		var err error
		configDir, err = store.DefaultConfigDir()
		if err != nil {
			log.Fatalf("could not determine config directory: %v", err)
		}
	}
	st, err := store.New(configDir)
	if err != nil {
		log.Fatalf("could not open store at %s: %v", configDir, err)
	}

	server := api.NewServer(st)
	log.Printf("Roundtable engine listening on %s (config: %s)", *host, configDir)
	err = server.ListenAndServe(*host, api.ServeOptions{
		AllowInsecureLAN: *allowInsecureLAN,
		UseTLS:           *useTLS,
		TLSCertFile:      *tlsCert,
		TLSKeyFile:       *tlsKey,
		CertDir:          configDir,
	})
	if err != nil {
		log.Fatal(err)
	}
}

func runUsers(args []string) {
	if len(args) < 1 || args[0] != "add" {
		fmt.Fprintln(os.Stderr, "usage: roundtable users add [--dir path] <username>")
		os.Exit(1)
	}
	fs := flag.NewFlagSet("users add", flag.ExitOnError)
	dir := fs.String("dir", "", "override the config directory (same store `serve --dir` uses)")
	fs.Parse(args[1:])
	positional := fs.Args()
	if len(positional) < 1 {
		fmt.Fprintln(os.Stderr, "usage: roundtable users add [--dir path] <username>")
		os.Exit(1)
	}
	username := positional[0]

	configDir := *dir
	if configDir == "" {
		var err error
		configDir, err = store.DefaultConfigDir()
		if err != nil {
			log.Fatalf("could not determine config directory: %v", err)
		}
	}
	st, err := store.New(configDir)
	if err != nil {
		log.Fatalf("could not open store at %s: %v", configDir, err)
	}
	fmt.Print("Password: ")
	var password string
	if _, err := fmt.Scanln(&password); err != nil {
		log.Fatalf("could not read password: %v", err)
	}
	if _, err := api.CreateFirstUser(st, username, password); err != nil {
		log.Fatal(err)
	}
	fmt.Printf("Added user %q.\n", username)
}

func runService(args []string) {
	if len(args) < 1 {
		fmt.Fprintln(os.Stderr, "usage: roundtable service <install|uninstall|status> [--host addr] [--dir path]")
		os.Exit(1)
	}
	fs := flag.NewFlagSet("service "+args[0], flag.ExitOnError)
	host := fs.String("host", "", "address to bind (passed through to serve; default 127.0.0.1:7890)")
	dir := fs.String("dir", "", "override the config directory (passed through to serve)")
	fs.Parse(args[1:])

	switch args[0] {
	case "install":
		if err := service.Install(service.Options{Host: *host, ConfigDir: *dir}); err != nil {
			log.Fatal(err)
		}
		fmt.Println("Installed. The engine will now start automatically at login.")
	case "uninstall":
		if err := service.Uninstall(); err != nil {
			log.Fatal(err)
		}
		fmt.Println("Uninstalled.")
	case "status":
		installed, err := service.Status()
		if err != nil {
			log.Fatal(err)
		}
		if installed {
			fmt.Println("installed")
		} else {
			fmt.Println("not installed")
		}
	default:
		fmt.Fprintln(os.Stderr, "usage: roundtable service <install|uninstall|status> [--host addr] [--dir path]")
		os.Exit(1)
	}
}
