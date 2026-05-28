use std::{fs, str};

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use clap::Parser;
use openssl::{
    encrypt::Decrypter,
    hash::MessageDigest,
    pkey::{PKey, Private},
    rsa::Padding,
    sign::Signer,
    symm::{Cipher, Crypter, Mode},
};

const RSA_ALGORITHM: &str = "rsa-oaep-sha256";
const RSA_INFO: &str = "otp-push password rsa-oaep-sha256 v1";
const EC_ALGORITHM: &str = "ecdh-aes-256-gcm";
const EC_INFO: &str = "otp-push password ecdh-aes-256-gcm v1";

#[derive(Debug, Parser)]
struct Args {
    #[arg(long)]
    private_key: String,

    #[arg(long)]
    secret: String,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    let private_key_pem = fs::read(args.private_key)?;
    let private_key = PKey::private_key_from_pem(&private_key_pem)?;
    let plaintext = decrypt_envelope(&private_key, &args.secret)?;

    println!("{}", String::from_utf8(plaintext)?);
    Ok(())
}

fn decrypt_envelope(
    private_key: &PKey<Private>,
    envelope: &str,
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let parts = envelope.split('.').collect::<Vec<_>>();
    if parts.first() != Some(&"v1") {
        return Err("unsupported envelope version".into());
    }

    match parts.get(1).copied() {
        Some(RSA_ALGORITHM) => decrypt_rsa(private_key, &parts),
        Some(EC_ALGORITHM) => decrypt_ec(private_key, &parts),
        _ => Err("unsupported envelope algorithm".into()),
    }
}

fn decrypt_rsa(
    private_key: &PKey<Private>,
    parts: &[&str],
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    if parts.len() != 4 {
        return Err("invalid RSA envelope".into());
    }

    let info = decode_part(parts[2])?;
    if info != RSA_INFO.as_bytes() {
        return Err("unexpected RSA info".into());
    }

    let ciphertext = decode_part(parts[3])?;
    let mut decrypter = Decrypter::new(private_key)?;
    decrypter.set_rsa_padding(Padding::PKCS1_OAEP)?;
    decrypter.set_rsa_oaep_md(MessageDigest::sha256())?;
    decrypter.set_rsa_mgf1_md(MessageDigest::sha256())?;

    let mut plaintext = vec![0; decrypter.decrypt_len(&ciphertext)?];
    let len = decrypter.decrypt(&ciphertext, &mut plaintext)?;
    plaintext.truncate(len);
    Ok(plaintext)
}

fn decrypt_ec(
    private_key: &PKey<Private>,
    parts: &[&str],
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    if parts.len() != 6 {
        return Err("invalid EC envelope".into());
    }

    let info = decode_part(parts[2])?;
    if info != EC_INFO.as_bytes() {
        return Err("unexpected EC info".into());
    }

    let ephemeral_public_key = decode_part(parts[3])?;
    let iv = decode_part(parts[4])?;
    let ciphertext_and_tag = decode_part(parts[5])?;
    if ciphertext_and_tag.len() < 16 {
        return Err("invalid EC ciphertext".into());
    }

    let peer_key = PKey::public_key_from_der(&ephemeral_public_key)?;
    let mut deriver = openssl::derive::Deriver::new(private_key)?;
    deriver.set_peer(&peer_key)?;
    let shared_secret = deriver.derive_to_vec()?;
    let aes_key = derive_ec_aes_key(&shared_secret, &ephemeral_public_key, &info)?;

    let tag_start = ciphertext_and_tag.len() - 16;
    let ciphertext = &ciphertext_and_tag[..tag_start];
    let tag = &ciphertext_and_tag[tag_start..];
    let mut crypter = Crypter::new(Cipher::aes_256_gcm(), Mode::Decrypt, &aes_key, Some(&iv))?;
    crypter.aad_update(&info)?;
    crypter.set_tag(tag)?;

    let mut plaintext = vec![0; ciphertext.len() + Cipher::aes_256_gcm().block_size()];
    let mut len = crypter.update(ciphertext, &mut plaintext)?;
    len += crypter.finalize(&mut plaintext[len..])?;
    plaintext.truncate(len);
    Ok(plaintext)
}

fn derive_ec_aes_key(
    shared_secret: &[u8],
    ephemeral_public_key: &[u8],
    info: &[u8],
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let pseudo_random_key = hmac_sha256(ephemeral_public_key, shared_secret)?;
    let mut expand_input = Vec::with_capacity(info.len() + 1);
    expand_input.extend_from_slice(info);
    expand_input.push(1);

    Ok(hmac_sha256(&pseudo_random_key, &expand_input)?)
}

fn hmac_sha256(key: &[u8], data: &[u8]) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let key = PKey::hmac(key)?;
    let mut signer = Signer::new(MessageDigest::sha256(), &key)?;
    signer.update(data)?;
    Ok(signer.sign_to_vec()?)
}

fn decode_part(part: &str) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    Ok(URL_SAFE_NO_PAD.decode(part)?)
}
