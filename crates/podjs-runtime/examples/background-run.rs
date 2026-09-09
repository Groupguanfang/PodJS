//! Diagnostic runner for an independently built, hash-verified background bundle.
use podjs_runtime::background::{self, Cancellation, Options, Status};
use sha2::{Digest, Sha256};
fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    anyhow::ensure!(args.len() == 3,"usage: background-run <bundle.js> <expected-sha256>");
    anyhow::ensure!(std::fs::metadata(&args[1])?.len() <= 1024*1024,"background bundle too large");
    let bytes = std::fs::read(&args[1])?;
    anyhow::ensure!(format!("{:x}",Sha256::digest(&bytes)) == args[2],"background bundle hash mismatch");
    let source = std::str::from_utf8(&bytes)?;
    let result = background::run(source,Options { app_id:"example.background",task_id:"refresh",budget_ms:5000,memory_bytes:16*1024*1024,payload_json:"null" },Cancellation::default());
    println!("{}",serde_json::to_string(&result)?);
    anyhow::ensure!(result.status == Status::Success,"background handler did not succeed");
    Ok(())
}
