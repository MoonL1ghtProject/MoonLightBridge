use rustls::{
    RootCertStore, ServerConfig,
    pki_types::{CertificateDer, PrivateKeyDer, pem::PemObject},
    server::WebPkiClientVerifier,
};
use std::{io, path::Path, sync::Arc};

/// Loads a PEM certificate chain, private key, and client CA into an mTLS configuration.
pub fn load_mtls_server_config(
    certificate_chain: impl AsRef<Path>,
    private_key: impl AsRef<Path>,
    client_ca: impl AsRef<Path>,
) -> io::Result<Arc<ServerConfig>> {
    let certificates = CertificateDer::pem_file_iter(certificate_chain)
        .map_err(pem_error)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(pem_error)?;
    if certificates.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "server certificate chain is empty",
        ));
    }

    let key = PrivateKeyDer::from_pem_file(private_key).map_err(pem_error)?;

    let mut roots = RootCertStore::empty();
    for certificate in CertificateDer::pem_file_iter(client_ca).map_err(pem_error)? {
        roots
            .add(certificate.map_err(pem_error)?)
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

fn pem_error(error: rustls::pki_types::pem::Error) -> io::Error {
    match error {
        rustls::pki_types::pem::Error::Io(error) => error,
        error => io::Error::new(io::ErrorKind::InvalidInput, error),
    }
}
