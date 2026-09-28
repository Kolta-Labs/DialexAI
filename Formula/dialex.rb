class Dialex < Formula
  desc "Sovereign multi-agent deliberation engine & CLI by Kolta Labs"
  homepage "https://github.com/Kolta-Labs/DialexAI"
  version "1.0.0"
  license "PolyForm-Noncommercial-1.0.0"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/Kolta-Labs/DialexAI/releases/download/v#{version}/dialex-engine-v#{version}-darwin-arm64.tar.gz"
      sha256 "0000000000000000000000000000000000000000000000000000000000000000"
    else
      url "https://github.com/Kolta-Labs/DialexAI/releases/download/v#{version}/dialex-engine-v#{version}-darwin-amd64.tar.gz"
      sha256 "0000000000000000000000000000000000000000000000000000000000000000"
    end
  end

  on_linux do
    if Hardware::CPU.arm?
      url "https://github.com/Kolta-Labs/DialexAI/releases/download/v#{version}/dialex-engine-v#{version}-linux-arm64.tar.gz"
      sha256 "0000000000000000000000000000000000000000000000000000000000000000"
    else
      url "https://github.com/Kolta-Labs/DialexAI/releases/download/v#{version}/dialex-engine-v#{version}-linux-amd64.tar.gz"
      sha256 "0000000000000000000000000000000000000000000000000000000000000000"
    end
  end

  def install
    bin.install "dialex-engine" => "dialex"
    bin.install_symlink "dialex" => "dialex-engine"
  end

  service do
    run [opt_bin/"dialex", "serve"]
    keep_alive true
    log_path var/"log/dialex.log"
    error_log_path var/"log/dialex.error.log"
    working_dir var/"dialex"
  end

  test do
    assert_match "dialex", shell_output("#{bin}/dialex --help")
  end
end
