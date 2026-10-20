use std::ffi::{c_char, CStr};

/// The n-th Fibonacci number, iteratively.
#[no_mangle]
pub extern "C" fn rust_fib(n: i32) -> i32 {
    let (mut a, mut b) = (0i32, 1i32);
    for _ in 0..n.max(0) {
        (a, b) = (b, a.wrapping_add(b));
    }
    a
}

/// Vowels in a NUL-terminated UTF-8 string; 0 for null. Uses std's string handling and an allocation.
#[no_mangle]
pub extern "C" fn rust_count_vowels(text: *const c_char) -> u32 {
    if text.is_null() {
        return 0;
    }
    let text = unsafe { CStr::from_ptr(text) }.to_string_lossy().to_lowercase();
    text.chars().filter(|c| "aeiou".contains(*c)).count() as u32
}
