use spki::{
    Document, SubjectPublicKeyInfoRef,
    der::{Decode as _, pem::PemLabel as _},
};

pub fn validate_x509_public_key_pem<E, ME>(pub_key: &str, map_err: ME) -> Result<(), E>
where
    ME: Fn(&str) -> E,
{
    let (label, document) = Document::from_pem(pub_key).map_err(|_| {
        map_err("pub_key must be a PEM-encoded X.509 SubjectPublicKeyInfo public key")
    })?;
    if label != SubjectPublicKeyInfoRef::PEM_LABEL {
        return Err(map_err(
            "pub_key must be a PEM-encoded X.509 SubjectPublicKeyInfo public key",
        ));
    }

    let public_key = SubjectPublicKeyInfoRef::from_der(document.as_bytes()).map_err(|_| {
        map_err("pub_key must be a PEM-encoded X.509 SubjectPublicKeyInfo public key")
    })?;

    let algorithm_oid = public_key.algorithm.oid.to_string();
    if algorithm_oid != "1.2.840.113549.1.1.1" && algorithm_oid != "1.2.840.10045.2.1" {
        return Err(map_err(
            "pub_key must use an RSA or EC public key algorithm for password encryption",
        ));
    }

    Ok(())
}
