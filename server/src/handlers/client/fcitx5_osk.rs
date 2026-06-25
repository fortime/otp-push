use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use common::{
    CreateOtpRequest, Fcitx5OskComboKey, Fcitx5OskComboKeyGroup, Fcitx5OskHttpApiResponse,
    Fcitx5OskKeyValue,
};
use uuid::Uuid;

use crate::{
    auth::ApiTokenAuth, error::AppError, services::otp as otp_service, state::SharedState,
};

fn char_to_key_value(c: char) -> Option<Fcitx5OskKeyValue> {
    let (kc, s) = match c {
        '1' => (10, "1"),
        '2' => (11, "2"),
        '3' => (12, "3"),
        '4' => (13, "4"),
        '5' => (14, "5"),
        '6' => (15, "6"),
        '7' => (16, "7"),
        '8' => (17, "8"),
        '9' => (18, "9"),
        '0' => (19, "0"),
        'q' => (24, "q"),
        'w' => (25, "w"),
        'e' => (26, "e"),
        'r' => (27, "r"),
        't' => (28, "t"),
        'y' => (29, "y"),
        'u' => (30, "u"),
        'i' => (31, "i"),
        'o' => (32, "o"),
        'p' => (33, "p"),
        'a' => (38, "a"),
        's' => (39, "s"),
        'd' => (40, "d"),
        'f' => (41, "f"),
        'g' => (42, "g"),
        'h' => (43, "h"),
        'j' => (44, "j"),
        'k' => (45, "k"),
        'l' => (46, "l"),
        'z' => (52, "z"),
        'x' => (53, "x"),
        'c' => (54, "c"),
        'v' => (55, "v"),
        'b' => (56, "b"),
        'n' => (57, "n"),
        'm' => (58, "m"),
        'Q' => (-24, "Q"),
        'W' => (-25, "W"),
        'E' => (-26, "E"),
        'R' => (-27, "R"),
        'T' => (-28, "T"),
        'Y' => (-29, "Y"),
        'U' => (-30, "U"),
        'I' => (-31, "I"),
        'O' => (-32, "O"),
        'P' => (-33, "P"),
        'A' => (-38, "A"),
        'S' => (-39, "S"),
        'D' => (-40, "D"),
        'F' => (-41, "F"),
        'G' => (-42, "G"),
        'H' => (-43, "H"),
        'J' => (-44, "J"),
        'K' => (-45, "K"),
        'L' => (-46, "L"),
        'Z' => (-52, "Z"),
        'X' => (-53, "X"),
        'C' => (-54, "C"),
        'V' => (-55, "V"),
        'B' => (-56, "B"),
        'N' => (-57, "N"),
        'M' => (-58, "M"),
        ' ' => (65, " "),
        '!' => (-10, "!"),
        '@' => (-11, "@"),
        '#' => (-12, "#"),
        '$' => (-13, "$"),
        '%' => (-14, "%"),
        '^' => (-15, "^"),
        '&' => (-16, "&"),
        '*' => (-17, "*"),
        '(' => (-18, "("),
        ')' => (-19, ")"),
        '-' => (20, "-"),
        '_' => (-20, "_"),
        '=' => (21, "="),
        '+' => (-21, "+"),
        '[' => (34, "["),
        '{' => (-34, "{"),
        ']' => (35, "]"),
        '}' => (-35, "}"),
        '\\' => (51, "\\"),
        '|' => (-51, "|"),
        ';' => (47, ";"),
        ':' => (-47, ":"),
        '\'' => (48, "'"),
        '"' => (-48, "\""),
        ',' => (59, ","),
        '<' => (-59, "<"),
        '.' => (60, "."),
        '>' => (-60, ">"),
        '/' => (61, "/"),
        '?' => (-61, "?"),
        '`' => (49, "`"),
        '~' => (-49, "~"),
        _ => return None,
    };

    Some(Fcitx5OskKeyValue {
        s: s.to_string(),
        c,
        kc,
        f: None,
    })
}

fn otp_to_groups(code: &str) -> Vec<Fcitx5OskComboKeyGroup> {
    let mut keys = Vec::new();

    for c in code.chars() {
        if let Some(kv) = char_to_key_value(c) {
            keys.push(Fcitx5OskComboKey::Key(kv));
        }
    }

    vec![Fcitx5OskComboKeyGroup { keys }]
}

pub async fn request(
    State(state): State<SharedState>,
    auth: ApiTokenAuth,
    Json(payload): Json<CreateOtpRequest>,
) -> Result<Json<Fcitx5OskHttpApiResponse>, AppError> {
    let pub_key = payload
        .pub_key
        .map(|key| key.trim().to_string())
        .filter(|key| !key.is_empty());
    if let Some(pub_key) = &pub_key {
        super::validate_x509_public_key_pem(pub_key)?;
    }

    let result = otp_service::create_otp_request(&state, &auth.token, pub_key).await?;

    let id_str = result.id.to_string();
    let short_id = if id_str.len() >= 6 {
        &id_str[id_str.len() - 6..]
    } else {
        &id_str
    };

    let prompts = vec![vec![(
        format!("Fill the request[#{short_id}] on your phone"),
        None,
    )]];

    let next_path = format!("/api/client/otp/fcitx5-osk/request/{}", result.id);
    let next = if let Some(base) = &state.config.base_url {
        Some(format!("{}{}", base.trim_end_matches('/'), next_path))
    } else {
        Some(format!("http://{}{}", state.config.listen_addr, next_path))
    };

    Ok(Json(Fcitx5OskHttpApiResponse {
        prompts,
        groups: Vec::new(),
        secret: None,
        next,
    }))
}

pub async fn poll(
    State(state): State<SharedState>,
    auth: ApiTokenAuth,
    Path(request_id): Path<Uuid>,
) -> Result<Response, AppError> {
    let code = otp_service::wait_for_otp(&state, &auth.token, request_id).await?;

    if let Some((encrypted, code)) = code {
        let (secret, groups) = if encrypted {
            (Some(code), vec![])
        } else {
            (None, otp_to_groups(&code))
        };
        return Ok(Json(Fcitx5OskHttpApiResponse {
            prompts: Vec::new(),
            groups,
            secret,
            next: None,
        })
        .into_response());
    }

    Ok(StatusCode::NO_CONTENT.into_response())
}
