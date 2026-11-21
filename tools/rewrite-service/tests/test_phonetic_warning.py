import unittest

import pipeline


class PhoneticWarningTest(unittest.TestCase):
    def test_root_without_ipa_gets_warning(self):
        lines = [
            "① ob 是盖上来。",
            "② scure 像 cure，治好就亮堂。",
            "③ 盖住亮堂就看不明白。",
            "④ 看不清 ——> 晦涩的。",
        ]
        out = pipeline._ensure_phonetic_warning(
            {"phonetic": "/əbˈskjʊə(r)/", "word": "obscure"}, "root", lines
        )
        self.assertEqual(len(out), 5)
        self.assertTrue(any("/əbˈskjʊə(r)/" in line for line in out))
        self.assertIn("——>", out[-1])

    def test_existing_ipa_is_kept(self):
        lines = [
            "① ap 是朝向。",
            "② 真读 /əˈprəʊpriət/，别按字母念。",
            "③ 合适 ——> 恰当的。",
        ]
        out = pipeline._ensure_phonetic_warning(
            {"phonetic": "/əˈprəʊpriət/"}, "root", lines
        )
        self.assertEqual(out, lines)

    def test_sound_method_is_not_touched(self):
        lines = [
            "① 读音像「猫」。",
            "② 猫 ——> 猫。",
        ]
        out = pipeline._ensure_phonetic_warning({"phonetic": "/kæt/"}, "sound", lines)
        self.assertEqual(out, lines)


if __name__ == "__main__":
    unittest.main()
