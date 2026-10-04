// 由 scripts 生成：确定性 .lybk 夹具（ASCII 密码，PBKDF2 100k + AES-256-GCM）
let fixtureHex = "4c59424b030a11181f262d343b424950575e656c05101b26313c47525d68737e4113ca438b802b379396c4d3c522131e6a9579e668b2b5a351ffb07ce4e01a34066798c880ece0c8312f82d8947a1d5899b50883c57bb13b8eabd72ea9918e0de9a2153c9dc7b1ad5ae965228ee3449798379d198b1f1d111b879cead409c9ffd78d6ccf9f4b8c8c2af2f1e0a55c2208c883c43f36676f4bc75d004cb424f76f7f21183452a1ff3aaabeb52631ce9f84c317dbe8a199265c0cc89f5da5b3d4cae3e1284cef86dd3641b65e0ba202c43da0add86a842f8ba6e03c1bb853dd655cd0d1708fd47e31c7d7fba2523e2eb520eb57550d2b205382104322a6f4f56b24dc15fb55d7fd84235304be6e47e109cfd0c2178254b88f89d97bf14b50d35d5edec3359087ad7ffe34d72d697dd8ec0cf6588f7f74ace3342d0a90ffe5f7a9e59894bc96687126f8ab776397824eab7407ae63f9cae0df403e18407012ef6bb1680e099bf7e5a5f5c64a95406821f6aa9e9f687741b9c04ec7c492c33eddd02008d867ab96f4ef4122604ca031771e62f747f14129cef8dce1d59b23c54d004a1e508e7d195c70beb8f67692e9f82f0a8324d71e8a20ee6c501b1263990846ae96ddcc8ba1645e476326609cfa10bee6d7319313d32c7c4d418c39dd5de5f5d9b7e7176a0938cfb3f74b033c3d098f8b0389587e138b643fc92affefd9f65584e8d12b8ddd4c1a8c55c4f627cc6bde81393005552b107379433b139549856c0ac38172ad5a1f0072d18efb0f668ccfc5fa47c15db47080cdfb34cc3ecf9efc40bbb864f6fe0cae8314c5c53684766189bf42e9bcd56c0fb8728a7cab8dc71c0416ab05d86e43856f4aff7f7dec06e908991afde9bcaaf4e65c68decf4829918eec4559611c712f682d4b"

// 明文（UTF-8 解码后应为）
let plaintext = """
{"version":1,"exportedAt":1700000000000,"appVersion":"fixture","companions":[{"id":100,"name":"小鱼","avatarUrl":null,"age":22,"personality":"温柔","backstory":null,"speakingStyle":null,"tags":null,"rawPrompt":null,"systemPrompt":null,"intimacy":7,"apiConfigId":null,"createdAt":1000,"updatedAt":2000}],"chatMessages":[{"id":900,"companionId":100,"content":"第一条","isFromUser":true,"timestamp":1000,"type":"TEXT","searchContent":"","fileFormat":"TEXT","linkString":""}],"chatGroups":[],"groupMessages":[],"memoryEntries":[],"tempMemories":[],"tokenUsages":[],"unifiedMemories":[],"diaries":[]}
"""

// 密码
let password = "yunian-backup-test"

// hex 换算出的派生密钥（用于单独断言 PBKDF2 一致性）
let keyHex = "b3a0b166c342adb56792e9122887d1059c1e3bf006ede51a0925f9f5926dd9e3"
