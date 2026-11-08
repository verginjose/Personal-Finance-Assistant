# Seccomp profiles for `pfa-system`

The `pfa` namespace enforces Baseline Pod Security, `pfa-system` does not (it
runs Vector and rootless BuildKit, which need `spc_t` and `Unconfined`-style
settings). A Localhost seccomp profile is one of the few controls that still
applies there.

`buildkitd.json` replaces the `seccompProfile: Unconfined` that rootless
BuildKit used to need. It is a deny-list, because the runtime needs a wide set
of syscalls and an allow-list is where builds break.

What it blocks, and why:

| Group | Syscalls | Effect |
|---|---|---|
| Cross-process access | `ptrace`, `process_vm_readv`, `process_vm_writev` | A build step can no longer inspect or control the daemon (`--oci-worker-no-process-sandbox` shares its namespaces). |
| Kernel observability | `bpf`, `perf_event_open`, `io_uring_*` | Removes the common memory-corruption/escape surface. |
| Memory trickery | `userfaultfd` | Removes the classic heap-grooming primitive. |
| Credentials | `add_key`, `request_key`, `keyctl` | No kernel keyring access. |
| Kernel code | `init_module`, `finit_module`, `delete_module`, `kexec_load`, `kexec_file_load` | No module loading or kexec. |
| Misc | `reboot`, `acct`, `swapon`, `swapoff`, `open_by_handle_at`, `nfsservctl`, `lookup_dcookie` | Not needed by a build; cheap to deny. |

## Install on the node

The kubelet resolves `localhostProfile` relative to its seccomp root
(`/var/lib/kubelet/seccomp` on this k3s install).

```bash
sudo install -D -m 0644 k8s/system/seccomp/buildkitd.json \
     /var/lib/kubelet/seccomp/profiles/buildkitd.json
kubectl -n pfa-system rollout restart deploy/buildkitd
kubectl -n pfa-system rollout status deploy/buildkitd
```

Then prove a build still runs: trigger one service via
`workflow_dispatch`, or submit a trivial Dockerfile with a `RUN` step through
`buildctl`. If the daemon crash-loops, read
`kubectl -n pfa-system logs deploy/buildkitd` and widen the list — do not
switch back to `Unconfined`.

This profile is a mitigation, not a boundary. A kernel bug reachable from the
remaining syscalls still reaches the host; the boundary is a VM
(`k8s/ci/README.md`, "Known limitations").
