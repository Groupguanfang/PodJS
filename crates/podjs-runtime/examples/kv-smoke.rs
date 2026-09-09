//! Device diagnostic. The supplied scratch directory must not already exist.
fn main() -> Result<(),Box<dyn std::error::Error>> {
    let root=std::path::PathBuf::from(std::env::args().nth(1).ok_or("scratch directory required")?);
    std::fs::create_dir(&root)?;
    let workers:Vec<_>=(0..4).map(|worker| {
        let root=root.clone();std::thread::spawn(move || {
            let mut kv=podjs_runtime::kv::KvStore::open(root.to_str());
            for index in 0..10 {kv.set(format!("{worker}:{index}"),serde_json::json!({"n":index,"text":"汉字😀"})).unwrap();}
        })
    }).collect();
    for worker in workers {worker.join().map_err(|_|"KV worker failed")?;}
    let mut first=podjs_runtime::kv::KvStore::open(root.to_str());
    let mut second=podjs_runtime::kv::KvStore::open(root.to_str());
    assert_eq!(first.keys()?.len(),40);
    second.set("fresh".into(),serde_json::json!(17))?;
    assert_eq!(first.get("fresh")?,Some(serde_json::json!(17)));
    first.delete("fresh")?; assert_eq!(second.get("fresh")?,None);
    std::fs::remove_file(root.join("podjs-kv.json"))?;
    std::fs::remove_file(root.join("podjs-kv.lock"))?;
    std::fs::remove_dir(root)?;
    println!("KV concurrent writes and cross-handle visibility passed");
    Ok(())
}
