# Encode URL for RemoteKeyProvider XOR obfuscation
url = 'https://suflow.cloud'
total = len(url)
parts = [[], [], []]
for i, ch in enumerate(url):
    parts[i % 3].append(ord(ch) ^ 0x5A)

print(f'Part1 ({len(parts[0])}) = byteArrayOf({", ".join(hex(b) for b in parts[0])})')
print(f'Part2 ({len(parts[1])}) = byteArrayOf({", ".join(hex(b) for b in parts[1])})')
print(f'Part3 ({len(parts[2])}) = byteArrayOf({", ".join(hex(b) for b in parts[2])})')

# Verify
decoded = []
i1 = i2 = i3 = 0
for i in range(total):
    if i % 3 == 0:
        decoded.append(parts[0][i1]); i1 += 1
    elif i % 3 == 1:
        decoded.append(parts[1][i2]); i2 += 1
    else:
        decoded.append(parts[2][i3]); i3 += 1
result = ''.join(chr(b ^ 0x5A) for b in decoded)
print(f'Verify: {result}')
print(f'Length: {total}')
