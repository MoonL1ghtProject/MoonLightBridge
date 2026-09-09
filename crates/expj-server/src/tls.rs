use std::{
    fs::File,
    io::{self, BufReader},
    path::Path,
    sync::Arc,
};
use tokio_rustls::rustls::{RootCertStore, ServerConfig, server::WebPkiClientVerifier};

pub fn load_mtls_server_config(
    certificate_chain: impl AsRef<Path>,
    private_key: impl AsRef<Path>,
    client_ca: impl AsRef<Path>,
) -> io::Result<Arc<ServerConfig>> {
    let certificates = rustls_pemfile::certs(&mut BufReader::new(File::open(certificate_chain)?))
        .collect::<Result<Vec<_>, _>>()?;
    if certificates.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "server certificate chain is empty",
        ));
    }

    let key = rustls_pemfile::private_key(&mut BufReader::new(File::open(private_key)?))?
        .ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "server private key is missing")
        })?;

    let mut roots = RootCertStore::empty();
    for certificate in rustls_pemfile::certs(&mut BufReader::new(File::open(client_ca)?)) {
        roots
            .add(certificate?)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    }
    if roots.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "client CA bundle is empty",
        ));
    }

    let verifier = WebPkiClientVerifier::builder(Arc::new(roots))
        .build()
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let config = ServerConfig::builder()
        .with_client_cert_verifier(verifier)
        .with_single_cert(certificates, key)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    Ok(Arc::new(config))
}
