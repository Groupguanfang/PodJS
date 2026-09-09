#include "journal_storage.h"
#include <cassert>
#include <filesystem>
#include <fstream>

template<class F> void rejects(F operation) { bool threw = false; try { operation(); } catch (...) { threw = true; } assert(threw); }
int main() {
  char directory[] = "/tmp/podjs-journal-storage-XXXXXX";
  assert(mkdtemp(directory)); const std::filesystem::path root(directory);
  {
    pod_store::JournalStorage store(root.string());
    assert(!store.read());
    rejects([&] { pod_store::JournalStorage competing(root.string()); });
    store.write("{\"v\":\"中文😀\"}"); assert(store.read() == "{\"v\":\"中文😀\"}");
    rejects([&] { store.write(std::string("\xc0\x80", 2)); });
    rejects([&] { store.write(std::string("a\0b", 3)); });
    rejects([&] { store.write(std::string(store.maxBytes + 1, 'a')); });
    assert(store.read() == "{\"v\":\"中文😀\"}");
    const auto stale = root / "podjs-notifications/journal.pending";
    { std::ofstream file(stale); file << "incomplete"; }
    assert(chmod(stale.c_str(), 0600) == 0);
    store.write("{\"v\":2}"); assert(store.read() == "{\"v\":2}");
    assert(symlink("journal.json", stale.c_str()) == 0);
    rejects([&] { store.write("{\"v\":3}"); });
    assert(store.read() == "{\"v\":2}"); assert(unlink(stale.c_str()) == 0);
  }
  { pod_store::JournalStorage reopened(root.string()); assert(reopened.read() == "{\"v\":2}"); }
  std::filesystem::remove_all(root);
}
