//! File receiver for authenticated companion sessions. Host serializes access to
//! each application-private root; no guest paths are accepted. Chunks on disk are
//! rehashed after restart, so receipt metadata cannot claim unwritten bytes.
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{fs::{self, File, OpenOptions}, io::{Read, Write}, path::{Path, PathBuf}};

pub const CHUNK_BYTES: usize = 65536;
pub const MAX_FILE_BYTES: u64 = 16 * 1024 * 1024;
pub const MAX_APP_BYTES: u64 = 32 * 1024 * 1024;
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FileManifest {
    pub transfer_id: String,
    pub size: u64,
    pub sha256: String,
    pub chunk_hashes: Vec<String>,
    pub mime: String,
}
fn hash(bytes: &[u8]) -> String { format!("{:x}", Sha256::digest(bytes)) }
fn valid_hash(value: &str) -> bool { value.len() == 64 && value.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)) }
impl FileManifest {
    pub fn validate(&self) -> Result<()> {
        ensure!(!self.transfer_id.is_empty() && self.transfer_id.len() <= 128 && self.transfer_id.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_'), "invalid transfer id");
        ensure!(self.size <= MAX_FILE_BYTES, "file quota exceeded");
        ensure!(valid_hash(&self.sha256), "invalid file hash");
        ensure!(self.chunk_hashes.len() == self.size.div_ceil(CHUNK_BYTES as u64) as usize, "invalid chunk count");
        ensure!(self.chunk_hashes.iter().all(|h| valid_hash(h)), "invalid chunk hash");
        ensure!(self.mime.len() <= 128 && !self.mime.chars().any(char::is_control), "invalid MIME");
        Ok(())
    }
}
fn regular(path: &Path) -> Result<()> { ensure!(fs::symlink_metadata(path)?.file_type().is_file(), "not a regular file"); Ok(()) }
fn write_new(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
    file.write_all(bytes)?; file.sync_all()?; Ok(())
}
fn sync_dir(path: &Path) -> Result<()> { File::open(path)?.sync_all()?; Ok(()) }
fn verified_file(path: &Path, manifest: &FileManifest) -> Result<bool> {
    if !path.try_exists()? { return Ok(false); }
    regular(path)?;
    if fs::metadata(path)?.len() != manifest.size { return Ok(false); }
    let mut input = File::open(path)?; let mut digest = Sha256::new(); let mut buffer = [0u8; CHUNK_BYTES];
    loop { let count = input.read(&mut buffer)?; if count == 0 { break; } digest.update(&buffer[..count]); }
    Ok(format!("{:x}", digest.finalize()) == manifest.sha256)
}
pub struct FileReceiver { root: PathBuf }
impl FileReceiver {
    /// Root must be an OS-private application directory, never shared storage.
    pub fn open(root: &Path) -> Result<Self> {
        fs::create_dir_all(root)?;
        ensure!(fs::symlink_metadata(root)?.file_type().is_dir(), "invalid receiver directory");
        let receiver = Self { root: root.to_path_buf() };
        receiver.recover()?;
        Ok(receiver)
    }
    fn recover(&self) -> Result<()> {
        for item in fs::read_dir(&self.root)? {
            let item = item?;
            ensure!(item.file_type()?.is_dir(), "unexpected receiver entry");
            let id = item.file_name().into_string().map_err(|_| anyhow::anyhow!("invalid transfer directory"))?;
            let directory = self.directory(&id)?;
            if !directory.join("manifest.json").try_exists()? {
                // Only discard known staging state, never unknown files.
                for child in fs::read_dir(&directory)? {
                    let child = child?; ensure!(child.file_name() == "manifest.tmp", "unknown incomplete transfer contents");
                    regular(&child.path())?;
                }
                if directory.join("manifest.tmp").try_exists()? { fs::remove_file(directory.join("manifest.tmp"))?; }
                fs::remove_dir(directory)?; sync_dir(&self.root)?; continue;
            }
            let manifest = self.manifest(&id)?;
            for name in ["manifest.tmp", "chunk.tmp", "complete.tmp"] {
                let path = directory.join(name);
                if path.try_exists()? { regular(&path)?; fs::remove_file(path)?; }
            }
            if verified_file(&directory.join("complete"), &manifest)? { self.cleanup_chunks(&directory, &manifest)?; }
            else if directory.join("complete").try_exists()? {
                regular(&directory.join("complete"))?; fs::remove_file(directory.join("complete"))?;
            }
            sync_dir(&directory)?;
        }
        Ok(())
    }
    fn cleanup_chunks(&self, directory: &Path, manifest: &FileManifest) -> Result<()> {
        for index in 0..manifest.chunk_hashes.len() {
            let path = directory.join(format!("chunk-{index}"));
            if path.try_exists()? { regular(&path)?; fs::remove_file(path)?; }
        }
        sync_dir(directory)
    }
    fn directory(&self, id: &str) -> Result<PathBuf> {
        ensure!(!id.is_empty() && id.len() <= 128 && id.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_'), "invalid transfer id");
        let path = self.root.join(id);
        ensure!(fs::symlink_metadata(&path)?.file_type().is_dir(), "invalid transfer directory"); Ok(path)
    }
    fn manifest(&self, id: &str) -> Result<FileManifest> {
        let path = self.directory(id)?.join("manifest.json"); regular(&path)?;
        ensure!(fs::metadata(&path)?.len() <= 32768, "manifest too large");
        let value: FileManifest = serde_json::from_slice(&fs::read(path)?)?; value.validate()?;
        ensure!(value.transfer_id == id, "manifest identity mismatch"); Ok(value)
    }
    pub fn offer(&self, manifest: &FileManifest) -> Result<()> {
        manifest.validate()?;
        let directory = self.root.join(&manifest.transfer_id);
        if directory.exists() { ensure!(self.manifest(&manifest.transfer_id)? == *manifest, "transfer identity reused"); return Ok(()); }
        // Reserve chunks plus assembly output. Replacement scratch is at most
        // one chunk and fits the same second-copy reservation.
        let mut reserved = manifest.size * 2;
        let mut count = 0;
        for item in fs::read_dir(&self.root)? {
            let item = item?; ensure!(item.file_type()?.is_dir(), "unexpected receiver entry");
            let id = item.file_name().into_string().map_err(|_| anyhow::anyhow!("invalid directory name"))?;
            let prior = self.manifest(&id)?;
            reserved += if verified_file(&item.path().join("complete"), &prior)? { prior.size } else { prior.size * 2 }; count += 1;
        }
        ensure!(reserved <= MAX_APP_BYTES && count < 128, "application file quota exceeded");
        fs::create_dir(&directory)?;
        write_new(&directory.join("manifest.tmp"), &serde_json::to_vec(manifest)?)?;
        fs::rename(directory.join("manifest.tmp"), directory.join("manifest.json"))?;
        sync_dir(&directory)?; sync_dir(&self.root)?; Ok(())
    }
    pub fn missing(&self, id: &str) -> Result<Vec<usize>> {
        let manifest = self.manifest(id)?; let directory = self.directory(id)?;
        if verified_file(&directory.join("complete"), &manifest)? { return Ok(Vec::new()); }
        let mut missing = Vec::new();
        for (index, expected) in manifest.chunk_hashes.iter().enumerate() {
            let path = directory.join(format!("chunk-{index}"));
            let expected_len = (manifest.size - index as u64 * CHUNK_BYTES as u64).min(CHUNK_BYTES as u64);
            let valid = regular(&path).is_ok() && fs::metadata(&path)?.len() == expected_len && hash(&fs::read(path)?) == *expected;
            if !valid { missing.push(index); }
        }
        Ok(missing)
    }
    /// Cancel/release exactly this transfer, including its completed artifact.
    pub fn cancel(&self, id: &str) -> Result<()> {
        let directory = self.directory(id)?;
        let files: Vec<PathBuf> = fs::read_dir(&directory)?.map(|entry| entry.map(|entry| entry.path())).collect::<std::io::Result<_>>()?;
        for path in &files { regular(path)?; }
        for path in files { fs::remove_file(path)?; }
        fs::remove_dir(&directory)?; sync_dir(&self.root)?; Ok(())
    }
    pub fn receive_chunk(&self, id: &str, index: usize, bytes: &[u8]) -> Result<()> {
        let manifest = self.manifest(id)?;
        ensure!(index < manifest.chunk_hashes.len(), "chunk index out of range");
        let expected_len = (manifest.size - index as u64 * CHUNK_BYTES as u64).min(CHUNK_BYTES as u64) as usize;
        ensure!(bytes.len() == expected_len && hash(bytes) == manifest.chunk_hashes[index], "chunk checksum mismatch");
        let directory = self.directory(id)?; let target = directory.join(format!("chunk-{index}"));
        if verified_file(&directory.join("complete"), &manifest)? { return Ok(()); }
        let temporary = directory.join("chunk.tmp");
        if temporary.exists() { regular(&temporary)?; fs::remove_file(&temporary)?; }
        write_new(&temporary, bytes)?; fs::rename(&temporary, target)?; sync_dir(&directory)?; Ok(())
    }
    pub fn finish(&self, id: &str) -> Result<PathBuf> {
        let manifest = self.manifest(id)?; let directory = self.directory(id)?;
        let destination = directory.join("complete");
        if verified_file(&destination, &manifest)? { self.cleanup_chunks(&directory, &manifest)?; return Ok(destination); }
        ensure!(self.missing(id)?.is_empty(), "file has missing or damaged chunks");
        let temp = directory.join("complete.tmp");
        if temp.exists() { regular(&temp)?; fs::remove_file(&temp)?; }
        let mut output = OpenOptions::new().write(true).create_new(true).open(&temp)?;
        let mut whole = Sha256::new(); let mut buffer = [0u8; CHUNK_BYTES];
        for index in 0..manifest.chunk_hashes.len() {
            let mut input = File::open(directory.join(format!("chunk-{index}")))?;
            loop { let count = input.read(&mut buffer)?; if count == 0 { break; } whole.update(&buffer[..count]); output.write_all(&buffer[..count])?; }
        }
        ensure!(format!("{:x}", whole.finalize()) == manifest.sha256, "whole file checksum mismatch");
        output.sync_all()?; drop(output);
        fs::rename(&temp, &destination)?; sync_dir(&directory)?;
        self.cleanup_chunks(&directory, &manifest)?;
        Ok(destination)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Temp(PathBuf);
    impl Temp { fn new() -> Self {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!("podjs-sync-{}-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos(), NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)));
        fs::create_dir(&path).unwrap(); Self(path)
    }}
    impl Drop for Temp { fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); } }
    fn manifest(bytes: &[u8]) -> FileManifest { FileManifest { transfer_id: "test".into(), size: bytes.len() as u64, sha256: hash(bytes), chunk_hashes: bytes.chunks(CHUNK_BYTES).map(hash).collect(), mime: "application/octet-stream".into() } }
    #[test] fn real_disk_resume_and_final_hash() {
        let temp = Temp::new(); let bytes: Vec<u8> = (0..CHUNK_BYTES * 3 + 7).map(|i| (i % 251) as u8).collect();
        let receiver = FileReceiver::open(&temp.0).unwrap(); receiver.offer(&manifest(&bytes)).unwrap();
        receiver.receive_chunk("test", 1, &bytes[CHUNK_BYTES..CHUNK_BYTES * 2]).unwrap(); drop(receiver);
        let receiver = FileReceiver::open(&temp.0).unwrap(); assert_eq!(receiver.missing("test").unwrap(), vec![0, 2, 3]);
        for (i, chunk) in bytes.chunks(CHUNK_BYTES).enumerate() { receiver.receive_chunk("test", i, chunk).unwrap(); }
        let path = receiver.finish("test").unwrap(); assert_eq!(fs::read(path).unwrap(), bytes);
    }
    #[test] fn damaged_chunk_is_requested_again() {
        let temp = Temp::new(); let r = FileReceiver::open(&temp.0).unwrap(); r.offer(&manifest(b"hello")).unwrap();
        assert!(r.receive_chunk("test", 0, b"wrong").is_err()); assert!(r.finish("test").is_err());
        r.receive_chunk("test", 0, b"hello").unwrap(); fs::write(temp.0.join("test/chunk-0"), b"other").unwrap();
        assert_eq!(r.missing("test").unwrap(), vec![0]);
    }
    #[test] fn rejects_paths_and_conflicting_manifests() {
        let temp = Temp::new(); let r = FileReceiver::open(&temp.0).unwrap(); let mut m = manifest(b"x");
        m.transfer_id = "../outside".into(); assert!(r.offer(&m).is_err());
        r.offer(&manifest(b"x")).unwrap(); assert!(r.offer(&manifest(b"y")).is_err());
        assert!(r.receive_chunk("test", 1, b"x").is_err());
    }
    #[test] fn empty_file_can_complete() {
        let temp = Temp::new(); let r = FileReceiver::open(&temp.0).unwrap(); r.offer(&manifest(b"")).unwrap();
        assert_eq!(fs::read(r.finish("test").unwrap()).unwrap(), b"");
    }
    #[test] fn cancellation_releases_only_selected_transfer() {
        let temp = Temp::new(); let r = FileReceiver::open(&temp.0).unwrap();
        r.offer(&manifest(b"a")).unwrap();
        let mut other = manifest(b"b"); other.transfer_id = "other".into(); r.offer(&other).unwrap();
        r.cancel("test").unwrap(); assert!(!temp.0.join("test").exists());
        assert_eq!(r.missing("other").unwrap(), vec![0]);
    }
    #[test] fn restart_cleans_staging_and_reuses_completed_file() {
        let temp = Temp::new(); let r = FileReceiver::open(&temp.0).unwrap();
        r.offer(&manifest(b"done")).unwrap(); r.receive_chunk("test", 0, b"done").unwrap();
        let completed = r.finish("test").unwrap();
        fs::write(temp.0.join("test/complete.tmp"), b"partial").unwrap();
        fs::write(temp.0.join("test/chunk-0"), b"done").unwrap();
        let staging = temp.0.join("interrupted"); fs::create_dir(&staging).unwrap(); fs::write(staging.join("manifest.tmp"), b"{").unwrap();
        let r = FileReceiver::open(&temp.0).unwrap();
        assert!(!staging.exists()); assert!(!temp.0.join("test/chunk-0").exists());
        assert!(!temp.0.join("test/complete.tmp").exists());
        assert!(r.missing("test").unwrap().is_empty()); assert_eq!(r.finish("test").unwrap(), completed);
    }
    #[test] fn admission_reserves_assembly_space() {
        let temp = Temp::new(); let r = FileReceiver::open(&temp.0).unwrap();
        let size = 9 * 1024 * 1024;
        let m = FileManifest { transfer_id: "one".into(), size, sha256: hash(b""), chunk_hashes: vec![hash(b""); size.div_ceil(CHUNK_BYTES as u64) as usize], mime: "application/octet-stream".into() };
        r.offer(&m).unwrap(); let mut second = m.clone(); second.transfer_id = "two".into();
        assert!(r.offer(&second).is_err()); assert!(!temp.0.join("two").exists());
    }
}
