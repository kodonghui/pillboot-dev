package org.semiproject.pillbadeumserver.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class SignupRequest {

    @NotBlank(message = "이메일 입력은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 100, message = "이메일은 100자 이하로 입력해주세요.")
    private String email;

    @NotBlank(message = "비밀번호 입력은 필수입니다.")
    @Size(min = 8, max = 20, message = "비밀번호는 8~20글자 사이로 입력해주세요.")
    private String password;

    @NotBlank(message = "닉네임 입력은 필수입니다.")
    @Size(min = 2, max = 10, message = "닉네임은 2~10글자 사이로 입력해주세요.")
    private String nickname;

    /* 사용자가 체크한 약관코드 목록. 필수약관이 모두 포함됐는지는 서비스에서 확인.*/
    @NotEmpty(message = "약관에 동의해주세요.")
    private List<Long> agreedTermsIds;

    public SignupRequest() {
    }

    public SignupRequest(String email, String password, String nickname, List<Long> agreedTermsIds) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.agreedTermsIds = agreedTermsIds;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public List<Long> getAgreedTermsIds() {
        return agreedTermsIds;
    }

    public void setAgreedTermsIds(List<Long> agreedTermsIds) {
        this.agreedTermsIds = agreedTermsIds;
    }

    @Override
    public String toString() {
        return "SignupRequest{" +
            "email='" + email + '\'' +
            ", nickname='" + nickname + '\'' +
            ", agreedTermsIds=" + agreedTermsIds +
            '}';
    }
}
