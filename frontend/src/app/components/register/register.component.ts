import { Component, OnInit } from '@angular/core';
import {FormControl, FormGroup, Validators} from "@angular/forms";
import {AuthService} from "../../services/auth.service";
import {Router} from "@angular/router";

@Component({
  selector: 'app-register',
  templateUrl: './register.component.html',
  styleUrls: ['./register.component.css']
})
export class RegisterComponent implements OnInit {

  registerForm = new FormGroup({
    email: new FormControl('', [Validators.required]), //Validators.email]),
    username: new FormControl('', [Validators.required]),
    password: new FormControl('', [Validators.required])
  })

  failedRegister = false;

  get email() {
    return this.registerForm.get('email');
  }

  get password() {
    return this.registerForm.get('password');
  }

  get username() {
    return this.registerForm.get('username');
  }

  constructor(
    private authService: AuthService,
    private router: Router
  ) { }

  ngOnInit(): void {}

  clearPassword() {
    this.registerForm.get('password')?.reset();
  }

  onSubmit() {
    if (this.registerForm.valid) {
      let email = this.registerForm.getRawValue().email;
      let username = this.registerForm.getRawValue().username;
      let password = this.registerForm.getRawValue().password;

      this.authService.register(email!.toString(), username!.toString(), password!.toString())
        .subscribe((result) => {
          if (result.status == 201) {
            console.log("Successful register")
            this.router.navigate(['/login']);
          } else {
            console.log("Error during register");
            this.clearPassword();
          }
        }, (error) => {
          this.failedRegister = true;
        });
    }
  }

}
